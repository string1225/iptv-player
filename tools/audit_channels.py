"""Audit every distinct exported route with real media reads, then retry all failures.

ExportPlaylists.java uses the app parser to produce entries.json first. No network
configuration is changed: --ipv6-source binds probes to a previously verified
local IPv6 address. Artifacts and media samples belong in an ignored directory.
"""
import argparse
import concurrent.futures
import hashlib
import json
import pathlib
import re
import subprocess
import threading
import time
import urllib.parse


class ProbeFailure(Exception):
    pass


def route_key(entry):
    return hashlib.sha256(json.dumps([entry["url"], entry["headers"]], sort_keys=True).encode()).hexdigest()


def attributes(line):
    return {m.group(1): m.group(2) or m.group(3) for m in
            re.finditer(r'([\w-]+)=(?:"([^"]*)"|([^,]*))', line)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--entries", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--ffmpeg", type=pathlib.Path, required=True)
    parser.add_argument("--ipv6-source")
    parser.add_argument("--workers", type=int, default=12)
    parser.add_argument("--resume", action="store_true", help="Keep passed results and recheck only rejected routes")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    samples = args.output / "samples"
    samples.mkdir(exist_ok=True)
    entries = json.loads(args.entries.read_text(encoding="utf-8"))
    routes = {route_key(e): e for e in entries}
    host_locks = {}
    for entry in routes.values():
        host_locks.setdefault(urllib.parse.urlsplit(entry["url"]).hostname, threading.Semaphore(2))
    records = {}
    if args.resume and (args.output / "results.json").exists():
        previous = json.loads((args.output / "results.json").read_text(encoding="utf-8"))
        records = {r["key"]: r for r in previous["results"] if r["key"] in routes}

    def fetch(url, entry, path, media=False, second=False):
        literal6 = ":" in urllib.parse.urlsplit(url).hostname
        command = ["curl.exe", "--globoff", "--noproxy", "*", "--location", "--ssl-revoke-best-effort",
                   "--connect-timeout", "5" if second else "3", "--max-time", "10" if second else "6",
                   "--max-filesize", "33554432" if media else "1048576", "--silent", "--show-error",
                   "--output", str(path), "--write-out", "%{json}"]
        if literal6:
            if not args.ipv6_source:
                raise ProbeFailure("IPv6 probe requires a verified source address")
            command += ["-6", "--interface", args.ipv6_source]
        else:
            command += ["-4"]
        headers = {"User-Agent": "Mozilla/5.0 (Linux; Android 10; Android TV) IPTVPlayer/0.3.3", **entry["headers"]}
        for name, value in headers.items():
            command += ["--header", f"{name}: {value}"]
        if media:
            command += ["--range", "0-2097151" if second else "0-786431"]
        result = subprocess.run([*command, url], capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=14)
        meta = json.loads(result.stdout) if result.stdout.strip() else {}
        # Live responses may never finish or ignore Range. A bounded partial sample
        # is useful only if the decoder below proves that it contains real media.
        partial_media = media and result.returncode in (28, 63) and path.exists() and path.stat().st_size >= 32768
        if result.returncode and not partial_media:
            raise ProbeFailure(result.stderr.strip()[:170] or f"curl exit {result.returncode}")
        if meta.get("http_code") not in (200, 206):
            raise ProbeFailure(f"HTTP {meta.get('http_code')}")
        if not path.exists() or path.stat().st_size == 0:
            raise ProbeFailure("Empty response")
        return meta.get("url_effective", url), meta

    def media_file(url, entry, key, second, label="media"):
        current = url
        init = None
        for depth in range(4):
            path = samples / f"{key}-{label}-{depth}.bin"
            current, meta = fetch(current, entry, path, media=depth > 0 or not re.search(r"\.m3u8?(?:\?|$)", current, re.I), second=second)
            data = path.read_bytes()
            if not data.lstrip(b"\xef\xbb\xbf \r\n\t").startswith(b"#EXTM3U"):
                if data.lstrip().lower().startswith((b"<html", b"<!doctype", b"{")):
                    raise ProbeFailure("Response is not a playlist or media stream")
                if init is not None:
                    path.write_bytes(init + data)
                return path
            text = data.decode("utf-8-sig", errors="replace")
            lines = [line.strip() for line in text.splitlines() if line.strip()]
            variants = []
            for index, line in enumerate(lines[:-1]):
                if line.startswith("#EXT-X-STREAM-INF:"):
                    attrs = attributes(line)
                    if not lines[index + 1].startswith("#"):
                        variants.append((int(attrs.get("BANDWIDTH", "999999999")), lines[index + 1]))
            if variants:
                current = urllib.parse.urljoin(current, min(variants)[1])
                continue
            for line in lines:
                if line.startswith("#EXT-X-KEY:") and attributes(line).get("METHOD", "NONE") != "NONE":
                    raise ProbeFailure("Encrypted stream could not be verified by the bounded media probe")
                if line.startswith("#EXT-X-MAP:"):
                    attrs = attributes(line)
                    if attrs.get("URI"):
                        init_path = samples / f"{key}-{label}-init.bin"
                        fetch(urllib.parse.urljoin(current, attrs["URI"]), entry, init_path, media=True, second=second)
                        init = init_path.read_bytes()
            links = [line for line in lines if not line.startswith("#")]
            if not links:
                raise ProbeFailure("Playlist has no media URI")
            current = urllib.parse.urljoin(current, links[0])
        raise ProbeFailure("Too many nested playlists")

    def probe(key, second=False):
        entry = routes[key]
        radio = any(word in (entry["group"] + entry["name"]).lower() for word in ("广播", "电台", "radio", " fm", "am频率"))
        started = time.monotonic()
        result = {"key": key, "url": entry["url"], "name": entry["name"], "group": entry["group"], "headers": entry["headers"], "attempt": 2 if second else 1}
        def decode_media(input_path, network=False):
            command = [str(args.ffmpeg.resolve()), "-hide_banner", "-nostdin", "-threads", "1", "-filter_threads", "1"]
            if network:
                command += ["-rw_timeout", "5000000"]
                if entry["headers"]:
                    command += ["-headers", "".join(f"{k}: {v}\r\n" for k, v in entry["headers"].items())]
            command += ["-analyzeduration", "2000000", "-probesize", "2097152", "-i", str(input_path), "-t", "0.2"]
            command += ["-vn", "-map", "0:a:0"] if radio else ["-map", "0:v:0?", "-map", "0:a:0", "-frames:v", "1"]
            decode = subprocess.run([*command, "-f", "null", "-"], capture_output=True, text=True,
                                    encoding="utf-8", errors="replace", timeout=15 if network else 10)
            streams = [line.strip() for line in decode.stderr.splitlines() if "Stream #0:" in line and ("Video:" in line or "Audio:" in line)]
            if decode.returncode or not any("Audio:" in line for line in streams):
                errors = [line.strip() for line in decode.stderr.splitlines() if any(word in line.lower() for word in ("invalid", "error", "not found", "matches no", "partial file"))]
                raise ProbeFailure("; ".join(errors[-2:])[:200] or "No decodable audio/video")
            return streams[:2], not any("Video:" in line for line in streams)
        with host_locks[urllib.parse.urlsplit(entry["url"]).hostname]:
            try:
                path = media_file(entry["url"], entry, key, second)
                try:
                    streams, audio_only = decode_media(path)
                except ProbeFailure:
                    if ":" in urllib.parse.urlsplit(entry["url"]).hostname:
                        raise
                    streams, audio_only = decode_media(entry["url"], network=True)
                result.update(valid=True, radio=radio or audio_only, streams=streams)
            except subprocess.TimeoutExpired:
                result.update(valid=False, reason="Media decoding exceeded the probe timeout")
            except (OSError, ValueError, ProbeFailure) as error:
                result.update(valid=False, reason=str(error)[:220])
        result["seconds"] = round(time.monotonic() - started, 3)
        return result

    def run(keys, second=False):
        done = 0
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
            jobs = {pool.submit(probe, key, second): key for key in keys}
            for future in concurrent.futures.as_completed(jobs):
                record = future.result()
                records[record["key"]] = record
                done += 1
                if done % 20 == 0 or done == len(keys):
                    print(f"{'retry' if second else 'first'} {done}/{len(keys)}; valid={sum(r['valid'] for r in records.values())}", flush=True)
                    (args.output / "progress.json").write_text(json.dumps(list(records.values()), ensure_ascii=False, indent=2), encoding="utf-8")

    if not args.resume:
        run(list(routes))
    failed = [key for key, record in records.items() if not record["valid"]]
    first_failures = {key: records[key].get("reason") for key in failed}
    print(f"Retrying all {len(failed)} failures after the complete first pass", flush=True)
    run(failed, second=True)
    for key in failed:
        records[key]["firstFailure"] = first_failures[key]
    report = {"entries": len(entries), "uniqueRoutes": len(routes), "validRoutes": sum(r["valid"] for r in records.values()), "results": list(records.values())}
    (args.output / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "results"}), flush=True)


if __name__ == "__main__":
    main()
