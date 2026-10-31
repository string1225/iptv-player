"""Android 10 audio regression with a real MP2 capture, AAC and a video-only fallback.

Build Debug with -PtestPlaylistBaseUrl=http://10.0.2.2:8877. Requires a dedicated
IPTV emulator; clears only that emulator's application data. Supply media files
at least 30 seconds long; --mp2-media is an HLS playlist with sibling TS
segments retaining MPEG-1 Layer II audio.
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
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=pathlib.Path, required=True)
    parser.add_argument("--mp2-media", type=pathlib.Path, required=True)
    parser.add_argument("--aac-media", type=pathlib.Path, required=True)
    parser.add_argument("--silent-media", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("artifacts/audio-smoke"))
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise ValueError("Use a dedicated IPTV emulator; application data will be cleared")
    args.output.mkdir(parents=True, exist_ok=True)
    base = "http://10.0.2.2:8877"
    requests = []
    transfers = []
    checks = []
    playlist = (
        f'央视频道,#genre#\nCCTV1,{base}/mp2.m3u8\nCCTV2,{base}/aac.mp4\n'
        f'CCTV3,{base}/silent.mp4\nCCTV3,{base}/aac.mp4?fallback\n'
    ).encode()

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        def log_message(self, *unused):
            pass

        def do_GET(self):
            path = urllib.parse.urlparse(self.path).path
            requests.append(self.path)
            if path == "/preferredipv6.playlist":
                self.send_response(200)
                self.send_header("Content-Length", str(len(playlist)))
                self.end_headers()
                self.wfile.write(playlist)
                return
            media = {"/mp2.m3u8": args.mp2_media, "/aac.mp4": args.aac_media,
                     "/silent.mp4": args.silent_media}.get(path)
            if media is None and path.endswith(".ts"):
                candidate = (args.mp2_media.parent / path.lstrip("/")).resolve()
                if candidate.parent == args.mp2_media.parent.resolve() and candidate.is_file():
                    media = candidate
            if media is None:
                self.send_error(404)
                return
            size = media.stat().st_size
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
            content_type = "application/vnd.apple.mpegurl" if path.endswith(".m3u8") else "video/mp2t" if path.endswith(".ts") else "video/mp4"
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(end - start + 1))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Connection", "close")
            self.close_connection = True
            if requested:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            transfer = {"path": self.path, "range": requested, "start": start, "end": end,
                        "size": size, "written": 0}
            transfers.append(transfer)
            try:
                with media.open("rb") as source:
                    source.seek(start)
                    remaining = end - start + 1
                    while remaining > 0:
                        chunk = source.read(min(65_536, remaining))
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        remaining -= len(chunk)
                        transfer["written"] += len(chunk)
            except (ConnectionError, OSError):
                pass

    def adb(*command):
        return subprocess.run([args.adb, "-s", args.serial, *map(str, command)],
                              capture_output=True, check=True, timeout=35).stdout.decode("utf-8", errors="replace")

    def key(*codes):
        adb("shell", "input", "keyevent", *codes)
        time.sleep(.25)

    def texts():
        result = adb("shell", "uiautomator", "dump", "/sdcard/iptv-audio.xml")
        if "dumped" not in result:
            return []
        raw = adb("shell", "cat", "/sdcard/iptv-audio.xml")
        (args.output / "latest-ui.xml").write_text(raw, encoding="utf-8")
        return [node.get("text", "") for node in ET.fromstring(raw).iter("node")]

    def wait(description, predicate):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            values = texts()
            if predicate(values):
                checks.append(description)
                print("PASS:", description, flush=True)
                return
            if len([v for v in values if v]) == 1:
                key(165)
            time.sleep(.3)
        raise AssertionError(f"{description}: {values}")

    def playing(values, channel, route=None):
        return channel in values and any("正在直播" in v and (route is None or route in v) for v in values)

    def route_state(url):
        key = hashlib.sha256(f"{len(url)}:{url}".encode()).hexdigest()
        try:
            raw = adb("shell", "run-as", "com.string.iptv", "cat", "shared_prefs/route_health.xml")
        except subprocess.CalledProcessError:
            return None  # Preferences do not exist before the first playback observation.
        return next((node.text for node in ET.fromstring(raw) if node.get("name") == key), None)

    if "IPTV" not in adb("emu", "avd", "name"):
        raise ValueError("Use an AVD whose name contains IPTV")
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 8877), Handler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        adb("install", "-r", args.apk)
        adb("shell", "am", "force-stop", "com.string.iptv")
        adb("shell", "pm", "clear", "com.string.iptv")
        adb("logcat", "-c")
        adb("shell", "am", "start", "-n", "com.string.iptv/.MainActivity")
        wait("HLS with captured MP2 audio starts playback", lambda v: playing(v, "CCTV1"))
        audio_log = adb("logcat", "-d", "-s", "IPTVAudio:I")
        assert "Mp2SoftwareDecoder" in audio_log, audio_log
        checks.append("MP2 uses the bundled software decoder")
        print("PASS:", checks[-1], flush=True)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline and route_state(base + "/mp2.m3u8") != "AVAILABLE":
            time.sleep(1)
        assert route_state(base + "/mp2.m3u8") == "AVAILABLE"
        checks.append("MP2 audio remains selected during stable playback")
        print("PASS:", checks[-1], flush=True)
        (args.output / "audio-flinger.txt").write_text(adb("shell", "dumpsys", "media.audio_flinger"), encoding="utf-8")
        key(85)
        wait("MP2 playback pauses", lambda v: any("已暂停" in t for t in v))
        key(85)
        wait("MP2 playback resumes", lambda v: playing(v, "CCTV1"))
        adb("logcat", "-c")
        key(20)
        wait("AAC playback still works", lambda v: playing(v, "CCTV2"))
        audio_log = adb("logcat", "-d", "-s", "IPTVAudio:I")
        assert "Audio decoder:" in audio_log and "Mp2SoftwareDecoder" not in audio_log, audio_log
        checks.append("AAC retains the platform audio decoder")
        print("PASS:", checks[-1], flush=True)
        key(19)
        wait("Switching back reinitializes MP2 playback", lambda v: playing(v, "CCTV1"))
        key(20, 20)
        wait("Video-only route automatically switches to the AAC backup", lambda v: playing(v, "CCTV3", "2/2"))
        assert route_state(base + "/silent.mp4") == "UNAVAILABLE"
        assert "/silent.mp4" in requests and "/aac.mp4?fallback" in requests
        checks.append("A video-only route is rejected rather than remembered as available")
        print("PASS:", checks[-1], flush=True)
        log = adb("logcat", "-d")
        (args.output / "logcat.txt").write_text(log, encoding="utf-8")
        assert "FATAL EXCEPTION" not in adb("logcat", "-d", "-b", "crash")
        assert "Invalid MP2 audio frame" not in log
        checks.append("No AndroidRuntime crash or MP2 decoding error")
        print("PASS:", checks[-1], flush=True)
    finally:
        (args.output / "logcat.txt").write_text(adb("logcat", "-d"), encoding="utf-8")
        adb("shell", "am", "force-stop", "com.string.iptv")
        server.shutdown()
        (args.output / "requests.json").write_text(json.dumps(requests, indent=2), encoding="utf-8")
        (args.output / "transfers.json").write_text(json.dumps(transfers, indent=2), encoding="utf-8")
    (args.output / "checks.json").write_text(json.dumps(checks, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
