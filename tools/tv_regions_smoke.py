"""Android 10 remote regression: two-level region menus, 12k local channels, collected sources.

Build Debug with testPlaylistBaseUrl=http://10.0.2.2:8877. Clears only a dedicated emulator.
"""
import argparse
import http.server
import json
import pathlib
import re
import subprocess
import threading
import time
import urllib.parse
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", default="emulator-5556")
    parser.add_argument("--apk", type=pathlib.Path, required=True)
    parser.add_argument("--media", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("artifacts/regions-smoke"))
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise ValueError("Use a dedicated emulator: this test clears application data")
    args.output.mkdir(parents=True, exist_ok=True)
    base = "http://10.0.2.2:8877"
    names = ["北京", "天津", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江", "上海", "江苏", "浙江", "安徽",
             "福建", "江西", "山东", "河南", "湖北", "湖南", "广东", "广西", "海南", "重庆", "四川", "贵州", "云南",
             "西藏", "陕西", "甘肃", "青海", "宁夏", "新疆", "香港", "澳门", "台湾"]
    local = [f"{name}新闻,{base}/sample.mp4?region={i}" for i, name in enumerate(names)]
    local += [f"七台河新闻综合,{base}/sample.mp4?qth", f"余姚新闻综合,{base}/sample.mp4?yuyao",
              f"广州新闻,{base}/sample.mp4?guangzhou", f"未识别的地方台,{base}/sample.mp4?other"]
    local += [f"广东 Station {i},{base}/sample.mp4?station={i}" for i in range(12_000)]
    domestic = (f"央视频道,#genre#\nCCTV1,{base}/sample.mp4?cctv1\nCCTV2,{base}/sample.mp4?cctv2\n地方频道,#genre#\n" + "\n".join(local)).encode()
    zbefine = (f'#EXTM3U\n#EXTINF:-1 group-title="央视",CCTV-1 综合\n{base}/sample.mp4?cctv1\n'
               f'#EXTINF:-1 group-title="广东",广州新闻\n{base}/sample.mp4?guangzhou2\n'
               f'#EXTINF:-1 group-title="江苏",南京新闻\n{base}/sample.mp4?nanjing\n').encode()
    myernestlu = f"地方频道,#genre#\n第一财经,{base}/sample.mp4?shanghai".encode()
    state = {"failed": False, "requests": []}

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        def handle(self):
            try:
                super().handle()
            except ConnectionError:
                pass
        def log_message(self, *unused):
            pass
        def body(self, data, content_type="text/plain"):
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Content-Type", content_type)
            self.end_headers()
            self.wfile.write(data)
        def do_GET(self):
            path = urllib.parse.urlsplit(self.path).path
            state["requests"].append(path)
            if path.endswith((".playlist", ".proxy")):
                if state["failed"]:
                    self.send_error(503)
                elif path.endswith(".proxy"):
                    self.body(b"<!doctype html><html>accelerator error</html>", "text/html")
                elif path == "/zbds4txt.playlist":
                    self.body(domestic)
                elif path == "/dzbefine.playlist":
                    self.body(zbefine)
                elif path == "/dmyernestlu.playlist":
                    self.body(myernestlu)
                else:
                    self.send_error(404)
                return
            if path != "/sample.mp4":
                self.send_error(404)
                return
            size = args.media.stat().st_size
            start, end = 0, size - 1
            range_value = self.headers.get("Range")
            if range_value:
                pair = range_value.removeprefix("bytes=").split("-", 1)
                start = int(pair[0] or "0")
                end = min(int(pair[1]) if pair[1] else end, end)
            if start >= size or start > end:
                self.send_error(416)
                return
            self.send_response(206 if range_value else 200)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Length", str(end - start + 1))
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
            except (OSError, ConnectionError):
                pass

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
    def dump():
        result = adb("shell", "uiautomator", "dump", "/sdcard/iptv-regions.xml")
        if "dumped" not in result:
            return ET.Element("hierarchy")
        raw = adb("shell", "cat", "/sdcard/iptv-regions.xml")
        (args.output / "latest-ui.xml").write_text(raw, encoding="utf-8")
        return ET.fromstring(raw)
    def texts():
        return [node.get("text", "") for node in dump().iter("node")]
    def focused():
        return next((n.get("text", "") for n in dump().iter("node") if n.get("focused") == "true"), "")
    def focus(text):
        assert focused() == text, (text, focused())
    def wait(description, predicate, timeout=25):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            values = texts()
            visible = [value for value in values if value]
            if len(visible) == 1 and re.fullmatch(r"\d{2}:\d{2}", visible[0]):
                key(165)  # The live information panel normally hides after six seconds.
                continue
            if predicate(values):
                checks.append(description)
                print("PASS:", description, flush=True)
                return values
            time.sleep(0.3)
        raise AssertionError(f"{description}: {values}")
    def launch():
        adb("shell", "am", "force-stop", "com.string.iptv")
        adb("shell", "am", "start", "-n", "com.string.iptv/.MainActivity")
        time.sleep(0.8)
    def screenshot(name):
        adb("shell", "screencap", "-p", "/sdcard/iptv-regions.png")
        adb("pull", "/sdcard/iptv-regions.png", args.output / f"{name}.png")
    def parent(text):
        for _ in range(40):
            if focused() == "Z · 央视频道":
                break
            key(19)
        for _ in range(40):
            if focused() == text:
                return
            key(20)
        raise AssertionError((text, focused()))

    try:
        if adb("shell", "getprop", "ro.build.version.sdk").strip() != "29":
            raise ValueError("Select the dedicated Android 10 / API 29 TV emulator")
        if "IPTV" not in adb("emu", "avd", "name"):
            raise ValueError("Select a dedicated IPTV test AVD before clearing application data")
        adb("install", "-r", args.apk)
        adb("shell", "pm", "clear", "com.string.iptv")
        adb("shell", "run-as", "com.string.iptv", "mkdir", "-p", "shared_prefs")
        legacy = ('<?xml version="1.0" encoding="utf-8"?><map>'
                  '<string name="default_channel">cctv-1 综合</string><string name="default_name">CCTV-1 综合</string>'
                  '<set name="favorites"><string>cctv-1 综合</string></set>'
                  '<set name="disabled_channels"><string>cctv-2 财经</string></set></map>').encode()
        subprocess.run([args.adb, "-s", args.serial, "shell", "run-as", "com.string.iptv", "tee", "shared_prefs/tv_preferences.xml"],
                       input=legacy, stdout=subprocess.DEVNULL, check=True, timeout=30)
        adb("shell", "settings", "put", "secure", "immersive_mode_confirmations", "confirmed")
        adb("logcat", "-c")
        launch()
        wait("default CCTV1 starts while 12k local stations load", lambda v: any(t in v for t in ("CCTV1", "CCTV-1 综合")) and any("正在直播" in t for t in v))
        key(23)
        wait("source fallback and CCTV aliases yield one 12042-channel catalog", lambda v: any("12042 个频道" in t for t in v))
        key(21)
        parent("Z · 央视频道")
        key(22)
        wait("legacy CCTV defaults favorites and disabled flags survive alias normalization",
             lambda v: any("CCTV1" in t and "★" in t and "♥" in t for t in v) and any("CCTV2" in t and "手动停用" in t for t in v))
        assert state["requests"].index("/dzbefine.proxy") < state["requests"].index("/dzbefine.playlist")
        assert state["requests"].index("/dmyernestlu.proxy") < state["requests"].index("/dmyernestlu.playlist")
        key(21)
        parent("Z · 地方频道")
        focus("Z · 地方频道")
        key(22)
        focus("北京")
        screenshot("two-level-menu")
        for name in names[1:] + ["其他地区"]:
            key(20)
            focus(name)
        wait("35 region rows scroll continuously and unknown stations remain in Other", lambda v: any("未识别的地方台" in t for t in v))
        key(22)
        assert "未识别的地方台" in focused()
        key(4)
        focus("其他地区")
        key(4)
        focus("Z · 地方频道")
        key(4)
        wait("Back returns through regions and parent before closing", lambda v: any(t in v for t in ("CCTV1", "CCTV-1 综合")) and "看电视" not in v)
        key(23)
        wait("reopened drawer returns to the playing category", lambda v: any("当前分类" in t for t in v))
        key(21)
        parent("D · 地方频道")
        key(22)
        focus("上海")
        key(20)
        focus("江苏")
        wait("collected sources have their own region menus", lambda v: any("南京新闻" in t for t in v))
        key(20)
        focus("广东")
        key(22)
        assert "广州新闻" in focused()
        key(82)
        wait("routes from Z and D merge without losing highlighted channel actions", lambda v: "选择播放线路（2 条）" in v and "设为默认频道：广州新闻" in v)
        key(4, 21, 21)
        focus("D · 地方频道")
        key(19, 19)
        focus("Z · 地方频道")
        key(22)
        # This parent remembers Other; walk up to Guangdong without entering the channel column.
        for _ in range(35):
            if focused() == "广东":
                break
            key(19)
        focus("广东")
        wait("large province is paginated independently", lambda v: any("第 1 / 121 页" in t for t in v))
        key(22, 167)
        wait("page changes retain the selected province", lambda v: any("第 2 / 121 页" in t for t in v) and any("Station 98" in t for t in v))
        screenshot("province-page")
        key(84)
        adb("shell", "input", "text", "station-11999")
        key(66)
        wait("global name search finds a station outside the displayed province page", lambda v: any("名称搜索 1 个" in t for t in v) and any("Station 11999" in t for t in v))
        key(82)
        key(*([19] * 12), 20, 23)
        key(4)
        state["failed"] = True
        launch()
        wait("regional default and collected lists survive restart when every source fails", lambda v: "广东 Station 11999" in v and any("正在直播" in t for t in v))
        key(23)
        wait("drawer restores the default's province and final page", lambda v: any("第 121 / 121 页" in t for t in v))
        key(21)
        focus("广东")
        key(21)
        focus("Z · 地方频道")
        screenshot("restored-default")
        key(4, 20, 165)
        wait("direct channel-down wraps only within the selected province", lambda v: "广东新闻" in v and any("正在直播" in t for t in v))
        crashes = adb("logcat", "-d", "-s", "AndroidRuntime:E")
        assert "FATAL EXCEPTION" not in crashes, crashes
        (args.output / "results.json").write_text(json.dumps({"checks": checks, "crashes": False}, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"PASS: {len(checks)} region checks; no AndroidRuntime crash", flush=True)
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
