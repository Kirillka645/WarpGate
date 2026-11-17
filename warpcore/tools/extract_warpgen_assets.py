#!/usr/bin/env python3
"""
Extracts the WARP config-generation assets (AmneziaWG I1 junk-packet masks and
the Clash template) from the warp-config-ru reference project and emits them as
a Go source file for WarpGate's Go core.

The reference project (MIT, (c) 2026 Dmitry Ganin) is the single source of
truth for these byte blobs; copying them through a script avoids transcription
errors in the multi-kilobyte hex masks.

Usage:
    git clone --depth 1 https://github.com/AppsGanin/warp-config-ru /tmp/ref
    python warpcore/tools/extract_warpgen_assets.py /tmp/ref warpcore/warpgen_assets.go

After regenerating, run `go test ./...`: the golden digests in
warpcore/warpgen_test.go pin the output format and will fail if the reference
project changed the templates.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

AWG_TS = "lib/builders/amneziawg.ts"
CLASH_YAML = "config/templates/clash.yaml"


def extract_awg_masks(ref: Path) -> list[str]:
    """Reads the I1_MASKS array literal from lib/builders/amneziawg.ts."""
    src = (ref / AWG_TS).read_text(encoding="utf-8")
    block = re.search(r"const I1_MASKS: string\[\] = \[(.*?)\];", src, re.S)
    if not block:
        raise SystemExit("I1_MASKS array not found")
    return re.findall(r'"(I1 = <b 0x[0-9a-fA-F]+>)"', block.group(1))


def extract_clash_template(ref: Path) -> str:
    return (ref / CLASH_YAML).read_text(encoding="utf-8")


def extract_yaml_anchors(template: str) -> dict[str, str]:
    """Pulls the <b 0x...> anchor values out of the Clash template."""
    anchors: dict[str, str] = {}
    for line in template.splitlines():
        m = re.match(r"^(i[12]-alt\d): &(\S+) (<b 0x[0-9a-fA-F]+>)\s*$", line)
        if m:
            anchors[m.group(1)] = m.group(3)
    return anchors


def go_quote(value: str) -> str:
    """Backtick-quotes a Go raw string, falling back to an interpreted string."""
    if "`" not in value:
        return "`" + value + "`"
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    ref = Path(sys.argv[1]).resolve()
    out = Path(sys.argv[2]).resolve()

    masks = extract_awg_masks(ref)
    template = extract_clash_template(ref)
    anchors = extract_yaml_anchors(template)

    missing = {"i1-alt1", "i1-alt2", "i1-alt3", "i2-alt3"} - anchors.keys()
    if missing:
        raise SystemExit(f"missing Clash anchors: {sorted(missing)}")

    lines = [
        "package warpcore",
        "",
        "// Code generated from the warp-config-ru reference project "
        "(MIT, (c) 2026 Dmitry Ganin). DO NOT EDIT BY HAND.",
        "//",
        "// The AmneziaWG I1 masks are junk-packet signatures that mimic a real",
        "// protocol handshake (QUIC / SIP) so a DPI middlebox sees plausible",
        "// traffic instead of a WireGuard datagram. The Clash template is the",
        "// mihomo/Clash configuration used by the same reference generator.",
        "",
        "const (",
    ]
    for i, mask in enumerate(masks, start=1):
        lines.append(f"\t// warpGenI1Mask{i} is an AmneziaWG 1.5 junk-packet signature.")
        lines.append(f"\twarpGenI1Mask{i} = {go_quote(mask)}")
    lines.append(")")
    lines.append("")
    lines.append("// warpGenClashTemplate is the mihomo/Clash config skeleton.")
    lines.append("// Substitutions are applied by BuildClash.")
    lines.append(f"const warpGenClashTemplate = {go_quote(template)}")
    lines.append("")
    lines.append("// Clash handshake masks referenced from the template's YAML anchors.")
    lines.append("const (")
    for name in ("i1-alt1", "i1-alt2", "i1-alt3", "i2-alt3"):
        lines.append(f"\t// warpGen{name.replace('-', '_')} is the {name} handshake mask.")
        lines.append(f"\twarpGen{name.replace('-', '_')} = {go_quote(anchors[name])}")
    lines.append(")")
    lines.append("")

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines), encoding="utf-8", newline="\n")

    print(f"awg masks: {len(masks)}")
    print(f"clash anchors: {len(anchors)}")
    print(f"template bytes: {len(template.encode('utf-8'))}")
    print(f"written: {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
