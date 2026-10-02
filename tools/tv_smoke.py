"""Remote-only Android emulator smoke test using five local playlist endpoints.

Build with -PtestPlaylistBaseUrl=http://10.0.2.2:8877, then pass --adb.
Uses a dedicated test installation: clears com.string.iptv data on the selected emulator.
The media file must be supplied as --media (an ordinary, decodable MP4).
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
    args = argparse.ArgumentParser()
    args.add_argument("--adb", required=True)
    args.add_argument("--serial", default="emulator-5554")
    args.add_argument("--media", type=pathlib.Path, required=True)
    args.add_argument("--apk", type=pathlib.Path, default=pathlib.Path("app/build/outputs/apk/debug/app-debug.apk"))
    args.add_argument("--output", type=pathlib.Path, default=pathlib.Path("artifacts/smoke"))
    options = args.parse_args()
    if not options.serial.startswith("emulator-"):
        raise ValueError("Use a dedicated emulator; this test clears application data")
    options.output.mkdir(parents=True, exist_ok=True)
    checks = []
    state = {"fail_playlists": False}
    base = "http://10.0.2.2:8877"
    playlist = (
        f"央视频道,#genre#\nCCTV1,{base}/bad1\nCCTV1,{base}/sample.mp4?channel=1\n"
        f"CCTV2,{base}/sample.mp4?channel=2\nCCTV2,{base}/sample.mp4?channel=2b\n"
        f"CCTV3,{base}/bad3a\nCCTV3,{base}/bad3b\n"
    ).encode()

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def handle(self):
            try:
                super().handle()
            except ConnectionError:
                pass  # Expected when the test force-stops the app.

        def log_message(self, *unused):
            pass

        def do_GET(self):
            path = urllib.parse.urlparse(self.path).path
            if path.endswith(".playlist"):
                if state["fail_playlists"]:
                    self.send_error(503)
                    return
                if path == "/zbds4txt.playlist":
                    body = playlist
                elif path == "/iptvorg.playlist":
                    body = f'#EXTM3U\n#EXTINF:-1 tvg-id="CCTV2.cn" group-title="央视频道",CCTV2\n{base}/sample.mp4?channel=2\n'.encode()
                else:
                    self.send_error(403)
                    return
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if path != "/sample.mp4":
                self.send_error(404)
                return
            size = options.media.stat().st_size
            start, end = 0, size - 1
            range_value = self.headers.get("Range")
            with (options.output / "http-requests.log").open("a", encoding="utf-8") as trace:
                trace.write(f"{self.path} range={range_value} size={size}\n")
            if range_value:
                pair = range_value.removeprefix("bytes=").split("-", 1)
                start = int(pair[0] or "0")
                end = min(int(pair[1]) if pair[1] else end, end)
            if start > end or start >= size:
                self.send_error(416)
                return
            self.send_response(206 if range_value else 200)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Length", str(end - start + 1))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Connection", "close")
            self.close_connection = True
            if range_value:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            try:
                with options.media.open("rb") as media:
                    media.seek(start)
                    remaining = end - start + 1
                    while remaining > 0:
                        chunk = media.read(min(65536, remaining))
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        remaining -= len(chunk)
            except (ConnectionError, OSError):
                pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 8877), Handler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()

    def adb(*command):
        result = subprocess.run([options.adb, "-s", options.serial, *map(str, command)],
                                capture_output=True, check=True, timeout=30)
        return result.stdout.decode("utf-8", errors="replace")

    def key(*codes):
        adb("shell", "input", "keyevent", *codes)
        time.sleep(0.25)

    def dump():
        adb("shell", "uiautomator", "dump", "/sdcard/iptv-smoke.xml")
        text = adb("shell", "cat", "/sdcard/iptv-smoke.xml")
        (options.output / "latest-ui.xml").write_text(text, encoding="utf-8")
        return ET.fromstring(text)

    def texts():
        return [n.get("text", "") for n in dump().iter("node")]

    def wait_for(description, predicate, timeout=25):
        end = time.monotonic() + timeout
        last = []
        while time.monotonic() < end:
            last = texts()
            if predicate(last):
                checks.append(description)
                print("PASS:", description, flush=True)
                return last
            time.sleep(0.4)
        raise AssertionError(f"{description}: {last}")

    def screenshot(name):
        adb("shell", "screencap", "-p", "/sdcard/iptv-smoke.png")
        adb("pull", "/sdcard/iptv-smoke.png", options.output / f"{name}.png")

    def launch():
        adb("shell", "am", "force-stop", "com.string.iptv")
        adb("shell", "am", "start", "-n", "com.string.iptv/.MainActivity")
        time.sleep(0.8)

    def menu_row(index):
        key(*([19] * 10))  # Clamp native ListView selection to its first row.
        key(*([20] * index), 23)

    try:
        adb("install", "-r", options.apk)
        adb("shell", "pm", "clear", "com.string.iptv")
        adb("shell", "settings", "put", "secure", "immersive_mode_confirmations", "confirmed")
        adb("logcat", "-c")
        launch()
        wait_for("startup plays CCTV1 and fails over from route 1 to route 2",
                 lambda values: "CCTV1" in values and any("正在直播" in v and "2/2" in v for v in values))
        screenshot("playback")
        key(23)
        wait_for("one OK press leaves channel drawer open", lambda values: any("当前分类" in v for v in values))
        screenshot("drawer")
        key(20, 82)
        wait_for("menu applies to highlighted CCTV2", lambda values: "设为默认频道：CCTV2" in values)
        menu_row(1)
        key(4)
        launch()
        wait_for("saved default CCTV2 automatically plays after process restart",
                 lambda values: "CCTV2" in values and any("正在直播" in v for v in values))
        prefs = adb("shell", "run-as", "com.string.iptv", "cat", "shared_prefs/tv_preferences.xml")
        assert 'name="default_channel">cctv2<' in prefs, prefs
        key(23)
        wait_for("drawer marks configured default", lambda values: any("CCTV2" in v and "★" in v for v in values))
        adb("shell", "input", "keyevent", "--longpress", 23)
        wait_for("long OK opens channel actions", lambda values: "添加收藏" in values)
        menu_row(2)
        wait_for("favorite survives UI refresh", lambda values: any("CCTV2" in v and "♥" in v for v in values))
        key(4, 22, 165)
        wait_for("right arrow changes route", lambda values: "CCTV2" in values and any("2/2" in v for v in values))
        key(20)
        wait_for("unavailable channel stops after both routes fail",
                 lambda values: "CCTV3" in values and any("已尝试全部线路" in v for v in values))
        screenshot("failed-routes")
        state["fail_playlists"] = True
        launch()
        wait_for("cached default still plays when every playlist server fails",
                 lambda values: "CCTV2" in values and any("正在直播" in v for v in values))
        key(82)
        menu_row(6)
        wait_for("source failures retain cached channels and show status",
                 lambda values: any("使用缓存" in v and "HTTP 503" in v for v in values))
        screenshot("source-status")
        adb("shell", "am", "force-stop", "com.string.iptv")
        adb("shell", "pm", "clear", "com.string.iptv")
        launch()
        wait_for("empty cache plus source failures yields usable error screen",
                 lambda values: "暂无可用频道" in values)
        key(82)
        wait_for("empty state still supports remote menu refresh", lambda values: "刷新全部频道源" in values)
        crashes = adb("logcat", "-d", "-s", "AndroidRuntime:E")
        assert "FATAL EXCEPTION" not in crashes, crashes
        (options.output / "results.json").write_text(json.dumps({"checks": checks, "crashes": False}, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"PASS: {len(checks)} emulator checks; no AndroidRuntime crash", flush=True)
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
