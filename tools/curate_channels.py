"""Build reviewed bundled snapshots from the app-parser export and media audit.

Only explicitly passed routes are retained. Audio-only streams are separated
from television channels so they cannot become silent-video fallback routes.
"""
import argparse
import collections
import hashlib
import json
import pathlib
import urllib.parse
from audit_channels import route_key


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--entries", type=pathlib.Path, required=True)
    parser.add_argument("--audit", type=pathlib.Path, required=True)
    parser.add_argument("--raw", type=pathlib.Path, required=True)
    parser.add_argument("--assets", type=pathlib.Path, default=pathlib.Path("app/src/main/assets/playlists"))
    parser.add_argument("--report", type=pathlib.Path, default=pathlib.Path("docs/channel-audit-20261031.json"))
    args = parser.parse_args()
    entries = json.loads(args.entries.read_text(encoding="utf-8"))
    audit = json.loads(args.audit.read_text(encoding="utf-8"))
    decisions = {r["key"]: r for r in audit["results"]}
    assert {route_key(e) for e in entries} == decisions.keys(), "Audit must cover every exported route"
    paths = {"preferredipv6": "IPV6.m3u", "zbds4m3u": "zbds4m3u.m3u", "zbds4txt": "zbds4txt.m3u"}
    summary = {}
    for source, filename in paths.items():
        original = [e for e in entries if e["source"] == source]
        retained, seen = [], set()
        for entry in original:
            record = decisions[route_key(entry)]
            if not record["valid"]:
                continue
            normalized = dict(entry)
            if record.get("radio"):
                normalized.update(group="广播频道", name=entry["name"] + "（广播）", tvgId="", logo="")
            key = (normalized["name"], normalized["group"], route_key(entry))
            if key not in seen:
                retained.append(normalized)
                seen.add(key)
        def attribute(value):
            assert not any(c in value for c in ('"', '\r', '\n'))
            return value
        lines = ["#EXTM3U", "# Reviewed 2026-10-31. See docs/channel-audit-20261031.json."]
        for entry in retained:
            attrs = {"group-title": entry["group"]}
            if entry["tvgId"]:
                attrs["tvg-id"] = entry["tvgId"]
            if entry["logo"]:
                attrs["tvg-logo"] = entry["logo"]
            lines.append('#EXTINF:-1 ' + ' '.join(f'{k}="{attribute(v)}"' for k, v in attrs.items()) + ',' + entry["name"])
            for key, value in entry["headers"].items():
                option = {"User-Agent": "http-user-agent", "Referer": "http-referrer"}[key]
                assert '\r' not in value and '\n' not in value
                lines.append(f"#EXTVLCOPT:{option}={value}")
            lines.append(entry["url"])
        data = ('\n'.join(lines) + '\n').encode()
        (args.assets / filename).write_bytes(data)
        summary[source] = {
            "originalEntries": len(original), "retainedEntries": len(retained),
            "rejectedEntries": sum(not decisions[route_key(e)]["valid"] for e in original),
            "duplicateEntriesRemoved": sum(decisions[route_key(e)]["valid"] for e in original) - len(retained),
            "radioEntries": sum(e["group"] == "广播频道" for e in retained),
            "ipv6LiteralEntries": sum(':' in urllib.parse.urlsplit(e["url"]).hostname for e in retained),
            "asset": filename, "sha256": hashlib.sha256(data).hexdigest(),
        }
    def reason(record):
        value = record.get("reason", "")
        if "CERT_EXPIRED" in value:
            return "TLS certificate expired"
        if "WRONG_PRINCIPAL" in value:
            return "TLS hostname mismatch"
        if "timed out" in value.lower() or "timeout" in value.lower():
            return "Connection or media probe timed out"
        if "Could not resolve host" in value:
            return "DNS lookup failed"
        return value
    report = {
        "date": "2026-10-31", "scope": "User IPV6.m3u and Z zbds; D and I are outside this audit",
        "method": "App parser; distinct URL+headers; direct HTTP playlist and media fetch; FFmpeg audio/video decode; every failure retried; validated IPv6 source binding; TLS verification enabled",
        "limit": "Results describe this network at audit time; rejected routes can work in a different network or at a later date. Audio-only results are labeled as radio, without verifying the broadcaster identity.",
        "originalEntries": len(entries), "distinctRoutes": len(decisions),
        "passedRoutes": sum(r["valid"] for r in decisions.values()),
        "rejectedRoutes": sum(not r["valid"] for r in decisions.values()),
        "sources": summary,
        "emptySourcesRemoved": ["zbds6m3u", "zbds6txt"],
        "rawSha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(args.raw.glob("*.playlist"))},
        "rejectionReasons": dict(collections.Counter(reason(r) for r in decisions.values() if not r["valid"])),
        "routes": [{"key": key, "url": r["url"], "headers": r["headers"], "passed": r["valid"],
                    **({"radio": r.get("radio", False), "streams": r["streams"]} if r["valid"] else {"reason": reason(r)})}
                   for key, r in sorted(decisions.items())],
    }
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k not in ("routes", "rawSha256")}, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()
