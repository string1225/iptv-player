"""Android 10 regression: 12k channels, cache-first refresh, Release downloads and install handoff.

Build debug with both testPlaylistBaseUrl and testUpdateBaseUrl=http://10.0.2.2:8877.
Supply signed 0.2.1 APKs (same signer / different signer). Clears only a dedicated emulator.
"""
import argparse
import hashlib
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
    parser.add_argument("--serial", default="emulator-5556")
    parser.add_argument("--media", type=pathlib.Path, required=True)
    parser.add_argument("--apk", type=pathlib.Path, required=True)
    parser.add_argument("--update-apk", type=pathlib.Path, required=True)
    parser.add_argument("--wrong-signer-apk", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("artifacts/update-smoke"))
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise ValueError("Use a dedicated emulator: this test clears application data")
    args.output.mkdir(parents=True, exist_ok=True)
    base = "http://10.0.2.2:8877"
    state = {"delay": 0, "fail": False, "release": None, "requests": [], "finished": 0}
    domestic = f"央视频道,#genre#\nCCTV1,{base}/sample.mp4?channel=1\nCCTV2,{base}/sample.mp4?channel=2\nCCTV3,{base}/sample.mp4?channel=3".encode()
    global_list = ("#EXTM3U\n" + "\n".join(
        f'#EXTINF:-1 group-title="大分类",Station {i}\n{base}/sample.mp4?station={i}' for i in range(12_345))).encode()

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        def handle(self):
            try:
                super().handle()
            except ConnectionError:
                pass  # Force-stopping the dedicated test app closes keep-alive connections.
        def log_message(self, *unused):
            pass

        def do_GET(self):
            path = urllib.parse.urlparse(self.path).path
            state["requests"].append(path)
            if path.endswith(".playlist"):
                time.sleep(state["delay"])
                state["finished"] += 1
                if state["fail"]:
                    self.send_error(503)
                    return
                if path == "/zbds4txt.playlist":
                    self.body(domestic)
                elif path == "/iptvorg.playlist":
                    self.body(global_list)
                else:
                    self.send_error(403)
                return
            if path == "/latest":
                apk = state["release"]
                if apk is None:
                    self.send_error(404)
                    return
                self.body(json.dumps({"tag_name": "v0.2.1", "draft": False, "prerelease": False,
                    "body": "Android TV test release", "assets": [{"name": "iptv-player.apk", "size": apk.stat().st_size,
                    "browser_download_url": "https://github.com/string1225/iptv-player/releases/download/v0.2.1/iptv-player.apk",
                    "digest": "sha256:" + hashlib.sha256(apk.read_bytes()).hexdigest()}]}).encode())
                return
            if path == "/proxy.apk":
                self.send_error(503)
                return
            if path == "/direct.apk":
                self.body(state["release"].read_bytes())
                return
            if path == "/sample.mp4":
                size = args.media.stat().st_size
                range_value = self.headers.get("Range")
                start, end = 0, size - 1
                if range_value:
                    pair = range_value.removeprefix("bytes=").split("-", 1)
                    start = int(pair[0] or "0")
                    end = min(int(pair[1]) if pair[1] else end, end)
                if start >= size or start > end:
                    self.send_error(416)
                    return
                self.send_response(206 if range_value else 200)
                self.send_header("Content-Length", str(end - start + 1))
                self.send_header("Content-Type", "video/mp4")
                self.send_header("Accept-Ranges", "bytes")
                if range_value:
                    self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
                self.end_headers()
                try:
                    with args.media.open("rb") as media:
                        media.seek(start)
                        remaining = end - start + 1
                        while remaining:
                            chunk = media.read(min(64 * 1024, remaining))
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                            remaining -= len(chunk)
                except (ConnectionError, OSError):
                    pass
                return
            self.send_error(404)

        def body(self, data):
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            try:
                for start in range(0, len(data), 64 * 1024):
                    self.wfile.write(data[start:start + 64 * 1024])
            except (ConnectionError, OSError) as error:
                print(f"Fixture connection closed: {self.path}: {error}", flush=True)

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 8877), Handler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    checks = []

    def adb(*command):
        return subprocess.run([args.adb, "-s", args.serial, *map(str, command)], capture_output=True,
                              timeout=35, check=True).stdout.decode("utf-8", errors="replace")

    def key(*codes):
        adb("shell", "input", "keyevent", *codes)
        time.sleep(0.25)

    def texts():
        result = adb("shell", "uiautomator", "dump", "/sdcard/iptv-update.xml")
        if "dumped" not in result:
            return []
        raw = adb("shell", "cat", "/sdcard/iptv-update.xml")
        (args.output / "latest-ui.xml").write_text(raw, encoding="utf-8")
        return [node.get("text", "") for node in ET.fromstring(raw).iter("node")]

    def wait(description, predicate, timeout=30, record=True):
        end = time.monotonic() + timeout
        values = []
        while time.monotonic() < end:
            values = texts()
            if predicate(values):
                if record:
                    checks.append(description)
                    print("PASS:", description, flush=True)
                return values
            time.sleep(0.3)
        raise AssertionError(f"{description}: {values}")

    def launch(clear=False):
        adb("shell", "am", "force-stop", "com.string.iptv")
        if clear:
            adb("shell", "pm", "clear", "com.string.iptv")
        adb("shell", "am", "start", "-n", "com.string.iptv/.MainActivity")
        time.sleep(0.8)

    def menu_row(index):
        key(*([19] * 12))
        key(*([20] * index), 23)

    def updates():
        key(165)
        wait("update menu waits for the default channel to load", lambda v: "CCTV1" in v or "CCTV2" in v, record=False)
        key(82)
        menu_row(7)

    def search(query):
        key(84, 123, *([67] * 40))
        adb("shell", "input", "text", query)
        key(66)

    def screenshot(name):
        adb("shell", "screencap", "-p", "/sdcard/iptv-update.png")
        adb("pull", "/sdcard/iptv-update.png", args.output / f"{name}.png")

    try:
        adb("install", "-r", args.apk)
        adb("shell", "appops", "set", "com.string.iptv", "REQUEST_INSTALL_PACKAGES", "deny")
        adb("logcat", "-c")
        launch(clear=True)
        wait("CCTV1 starts with a 12k-channel source", lambda v: "CCTV1" in v and any("正在直播" in t for t in v))
        key(23)
        values = wait("default drawer is a 3-channel group with no all-channels entry",
                      lambda v: any("12348 个频道" in t and "当前分类 3 个" in t for t in v))
        assert "全部频道" not in values
        key(21)
        key(20)
        key(22)
        wait("large group is divided into 124 pages", lambda v: any("第 1 / 124 页" in t for t in v))
        key(167)
        wait("remote channel-down changes to the second page", lambda v: any("第 2 / 124 页" in t for t in v) and any("Station 100" in t for t in v))
        screenshot("paged-group")
        key(82)
        wait("actions target the highlighted station after a page change", lambda v: "设为默认频道：Station 100" in v)
        key(4)
        search("station-12344")
        wait("name search finds the final station across 12k channels", lambda v: any("名称搜索 1 个" in t for t in v) and any("Station 12344" in t for t in v))
        search("CCTV-2")
        wait("name search crosses the selected group and normalizes hyphens", lambda v: any("名称搜索 1 个" in t for t in v) and any("CCTV2" in t for t in v))
        screenshot("name-search")
        key(82)
        menu_row(1)
        key(4)
        state["delay"] = 10
        state["finished"] = 0
        launch()
        wait("cached default plays before any slow remote playlist finishes",
             lambda v: "CCTV2" in v and any("正在直播" in t for t in v), timeout=8)
        assert state["finished"] == 0, state
        state["delay"] = 0
        time.sleep(3)
        updates()
        key(23)
        wait("a repository without releases has a usable update screen", lambda v: any("仓库尚未发布" in t for t in v), timeout=25)
        state["release"] = args.update_apk
        state["requests"].clear()
        key(23)
        wait("manual check discovers a stable newer release", lambda v: any("发现新版 0.2.1" in t for t in v))
        key(23)
        wait("manual download falls back from accelerator and validates signed APK", lambda v: any("已下载并校验" in t for t in v))
        assert state["requests"].index("/proxy.apk") < state["requests"].index("/direct.apk"), state
        screenshot("update-ready")
        key(23)
        wait("install opens Android unknown-source permission settings", lambda v: any("Allow from this source" in t for t in v))
        adb("shell", "appops", "set", "com.string.iptv", "REQUEST_INSTALL_PACKAGES", "allow")
        key(4)
        wait("granted permission hands APK to the system confirmation screen", lambda v: "Install" in v and any("update" in t.lower() for t in v))
        screenshot("install-confirmation")
        key(4)
        launch()
        updates()
        wait("downloaded update survives process restart", lambda v: any("已下载，可安装" in t for t in v))
        key(4)
        state["requests"].clear()
        launch(clear=True)
        updates()
        wait("automatic update checks and downloads without manual check", lambda v: any("已下载并校验" in t for t in v), timeout=40)
        assert "/latest" in state["requests"] and "/direct.apk" in state["requests"]
        assert "com.string.iptv/.MainActivity" in adb("shell", "dumpsys", "activity", "activities")
        state["release"] = args.wrong_signer_apk
        key(4)
        launch(clear=True)
        updates()
        wait("automatic update rejects an APK signed by another key", lambda v: any("签名与当前安装不一致" in t for t in v), timeout=40)
        cached = adb("shell", "run-as", "com.string.iptv", "ls", "files/updates")
        assert "update-2001.apk" not in cached, cached
        screenshot("wrong-signature")
        crashes = adb("logcat", "-d", "-s", "AndroidRuntime:E")
        assert "FATAL EXCEPTION" not in crashes, crashes
        result = {"checks": checks, "crashes": False, "requests": state["requests"]}
        (args.output / "results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"PASS: {len(checks)} feature checks; no AndroidRuntime crash", flush=True)
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
