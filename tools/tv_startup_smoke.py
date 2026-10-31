"""Verify playback before catalog arrival, upgrade migration and favorites ordering.

Build Debug with testPlaylistBaseUrl=http://10.0.2.2:8877 and
testStartupStreamUrl=http://10.0.2.2:8877/startup.mp4. Dedicated IPTV AVD only.
"""
import argparse
import http.server
import json
import pathlib
import subprocess
import threading
import time
import urllib.parse
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=pathlib.Path, required=True)
    parser.add_argument("--media", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("artifacts/startup-smoke"))
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise ValueError("Use a dedicated IPTV emulator; application data will be cleared")
    args.output.mkdir(parents=True, exist_ok=True)
    checks, requests = [], []
    release_lists = threading.Event()
    state = {"fail": False}
    base = "http://10.0.2.2:8877"
    playlist = (f'卫视频道,#genre#\n东方卫视,{base}/startup.mp4\n'
                f'东方卫视,{base}/backup.mp4\n央视频道,#genre#\nCCTV1,{base}/cctv1.mp4\n').encode()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *unused):
            pass

        def do_GET(self):
            path = urllib.parse.urlsplit(self.path).path
            event = {"path": path, "at": time.monotonic()}
            requests.append(event)
            if path.endswith((".playlist", ".proxy")):
                release_lists.wait(20)
                if state["fail"] or path != "/zbds4txt.playlist":
                    self.send_error(503)
                    return
                body = playlist
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                event["responseAt"] = time.monotonic()
                try:
                    self.wfile.write(body)
                except OSError:
                    pass
                return
            if path not in ("/startup.mp4", "/backup.mp4", "/cctv1.mp4"):
                self.send_error(404)
                return
            size = args.media.stat().st_size
            start, end = 0, size - 1
            requested = self.headers.get("Range")
            if requested:
                first, last = requested.removeprefix("bytes=").split("-", 1)
                start = int(first or 0)
                end = min(int(last) if last else end, end)
            if start > end or start >= size:
                self.send_error(416)
                return
            self.send_response(206 if requested else 200)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Length", str(end - start + 1))
            self.send_header("Accept-Ranges", "bytes")
            if requested:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            try:
                with args.media.open("rb") as source:
                    source.seek(start)
                    remaining = end - start + 1
                    while remaining:
                        chunk = source.read(min(65536, remaining))
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        remaining -= len(chunk)
            except OSError:
                pass

    def adb(*command):
        return subprocess.run([args.adb, "-s", args.serial, *map(str, command)],
                              capture_output=True, check=True, timeout=35).stdout.decode("utf-8", errors="replace")

    def key(*codes):
        adb("shell", "input", "keyevent", *codes)
        time.sleep(.25)

    def nodes():
        adb("shell", "uiautomator", "dump", "/sdcard/iptv-startup.xml")
        raw = adb("shell", "cat", "/sdcard/iptv-startup.xml")
        (args.output / "latest-ui.xml").write_text(raw, encoding="utf-8")
        return list(ET.fromstring(raw).iter("node"))

    def wait(description, predicate, timeout=25):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            values = nodes()
            if predicate(values):
                checks.append(description)
                print("PASS:", description, flush=True)
                return values
            key(165)  # Keep playback information visible without opening the drawer.
        raise AssertionError(f"{description}: {[v.get('text') for v in values]}")

    def playing(values, name):
        texts = [v.get("text", "") for v in values]
        return name in texts and any("正在直播" in v for v in texts)

    def launch():
        adb("shell", "am", "force-stop", "com.string.iptv")
        adb("shell", "am", "start", "-n", "com.string.iptv/.MainActivity")

    def prefs():
        return adb("shell", "run-as", "com.string.iptv", "cat", "shared_prefs/tv_preferences.xml")

    if "IPTV" not in adb("emu", "avd", "name"):
        raise ValueError("Use an AVD whose name contains IPTV")
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 8877), Handler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        adb("install", "-r", args.apk)
        adb("shell", "am", "force-stop", "com.string.iptv")
        adb("shell", "pm", "clear", "com.string.iptv")
        adb("shell", "run-as", "com.string.iptv", "mkdir", "-p", "shared_prefs")
        old = ('<?xml version="1.0" encoding="utf-8"?><map>'
               '<string name="default_channel">cctv1</string><string name="default_name">CCTV1</string>'
               '<set name="favorites"><string>cctv1</string></set><boolean name="auto_update" value="false"/></map>')
        subprocess.run([args.adb, "-s", args.serial, "shell", "run-as", "com.string.iptv", "tee",
                        "shared_prefs/tv_preferences.xml"], input=old.encode(), capture_output=True, check=True)
        adb("shell", "settings", "put", "secure", "immersive_mode_confirmations", "confirmed")
        adb("logcat", "-c")
        launch()
        wait("upgrade plays Dongfang while every playlist response is blocked", lambda v: playing(v, "东方卫视"))
        assert not any("responseAt" in r for r in requests)
        assert any(r["path"] == "/startup.mp4" for r in requests)
        assert "instant_channel" in prefs() and "东方卫视" in prefs()
        checks.append("upgrade stores a tiny startup record independently of the catalog")
        # Keep the player running across catalog arrival and compare media connections.
        connections = len([r for r in requests if r["path"] == "/startup.mp4"])
        release_lists.set()
        time.sleep(3)
        key(23)
        wait("catalog appears asynchronously", lambda v: any("当前分类" in n.get("text", "") for n in v))
        key(21, 19, 19)
        values = nodes()
        favorites = next(n for n in values if n.get("text") == "我的收藏")
        assert favorites.get("focused") == "true", favorites.attrib
        wait("favorites is the first group and preserves the existing favorite", lambda v: any("CCTV1" in n.get("text", "") for n in v))
        assert connections == len([r for r in requests if r["path"] == "/startup.mp4"]), requests
        checks.append("background catalog arrival does not reconnect the active stream")
        key(22, 23)
        wait("saved favorite can be selected", lambda v: playing(v, "CCTV1"))
        key(82)
        wait("channel actions open", lambda v: any(n.get("text", "").startswith("设为默认频道") for n in v))
        key(*([19] * 12), 20, 23)
        assert 'name="default_channel">cctv1<' in prefs(), prefs()
        adb("shell", "am", "force-stop", "com.string.iptv")
        adb("shell", "run-as", "com.string.iptv", "rm", "-rf", "files/playlists")
        state["fail"] = True
        requests.clear()
        launch()
        wait("user-selected default plays with all playlists unavailable and no catalog cache", lambda v: playing(v, "CCTV1"))
        assert any(r["path"] == "/cctv1.mp4" for r in requests)
        log = adb("logcat", "-d")
        (args.output / "logcat.txt").write_text(log, encoding="utf-8")
        assert "FATAL EXCEPTION" not in log
        checks.append("no Android runtime crash")
    finally:
        release_lists.set()
        server.shutdown()
        (args.output / "results.json").write_text(json.dumps({"checks": checks, "requests": requests}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{len(checks)} checks passed", flush=True)


if __name__ == "__main__":
    main()
