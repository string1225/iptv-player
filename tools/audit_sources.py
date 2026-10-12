"""Audit public playlist links without executing repository content or probing every TV stream.

Counts are HTTP(S) playlist entries, not a claim of playable channels.
Raw files are stored only under the requested ignored cache directory.
"""
import argparse
import concurrent.futures
import hashlib
import ipaddress
import json
import pathlib
import re
import urllib.error
import urllib.parse
import urllib.request

LIMIT = 8 * 1024 * 1024


def inspect_playlist(text):
    entries = []
    group = "未分类"
    pending = None
    for raw in text.lstrip("\ufeff").splitlines():
        line = raw.strip()
        if line.startswith("#EXTINF:"):
            match = re.search(r'group-title=["\']([^"\']*)', line, re.I)
            pending = (line.rsplit(",", 1)[-1], match.group(1) if match else group)
        elif line.startswith("#EXTGRP:"):
            group = line.split(":", 1)[1]
            if pending:
                pending = (pending[0], group)
        elif line.startswith("#"):
            continue
        elif pending:
            entries.append((*pending, line))
            pending = None
        elif "," in line:
            name, address = line.split(",", 1)
            if address.strip().lower() == "#genre#":
                group = name
            else:
                entries.extend((name, group, route.split("$", 1)[0]) for route in address.split("#"))
    valid = []
    v4 = v6 = domains = 0
    for name, category, route in entries:
        try:
            url = urllib.parse.urlsplit(route.split("|", 1)[0].strip())
            if url.scheme not in ("http", "https") or not url.hostname:
                continue
            valid.append((name, category))
            try:
                ip = ipaddress.ip_address(url.hostname)
                v6 += ip.version == 6
                v4 += ip.version == 4
            except ValueError:
                domains += 1
        except ValueError:
            continue
    return {"entries": len(valid), "names": len(set(n for n, _ in valid)), "ipv4Literals": v4,
            "ipv6Literals": v6, "domainHosts": domains, "groups": sorted(set(g for _, g in valid))}


def audit(source, directory):
    result = {"id": source["id"], "url": source["url"]}
    # Invalid HTML returned by an accelerator must not prevent testing the original link.
    urls = [source["url"]]
    if source["url"].startswith("https://raw.githubusercontent.com/"):
        urls.insert(0, "https://gh-proxy.org/" + source["url"])
    attempts = []
    for url in urls:
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "IPTVPlayer-SourceAudit/0.3.0"})
            with urllib.request.urlopen(request, timeout=15) as response:
                data = response.read(LIMIT + 1)
                if len(data) > LIMIT:
                    raise ValueError("playlist exceeds 8 MiB")
                text = data.decode("utf-8-sig")
                counts = inspect_playlist(text)
                if not counts["entries"] or text.lstrip().lower().startswith(("<!doctype", "<html")):
                    raise ValueError("response is not an HTTP(S) playlist")
                (directory / (source["id"] + ".playlist")).write_text(text, encoding="utf-8")
                return {**result, "status": response.status, "downloadUrl": url, "bytes": len(data),
                        "sha256": hashlib.sha256(data).hexdigest(), **counts, "attempts": attempts}
        except (OSError, ValueError, urllib.error.URLError) as error:
            attempts.append({"url": url, "error": str(error)[:200]})
    return {**result, "status": "failed", "attempts": attempts}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", type=pathlib.Path, default=pathlib.Path("docs/iptv-sources.json"))
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("tools/.cache/source-audit.json"))
    parser.add_argument("--cache", type=pathlib.Path, default=pathlib.Path("tools/.cache/source-audit"))
    args = parser.parse_args()
    inventory = json.loads(args.inventory.read_text(encoding="utf-8"))
    args.cache.mkdir(parents=True, exist_ok=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(lambda source: audit(source, args.cache), inventory["sources"]))
    args.output.write_text(json.dumps({"collectedOn": inventory["collectedOn"], "results": results},
                                    ensure_ascii=False, indent=2), encoding="utf-8")
    for result in results:
        print(result["id"], result["status"], result.get("entries", 0), flush=True)


if __name__ == "__main__":
    main()
