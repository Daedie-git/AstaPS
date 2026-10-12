#!/usr/bin/env python3
"""Enumerate receive-route candidates, not end-to-end supported workflows."""
import argparse
import json
from pathlib import Path
import re


def code_only(source):
    # Consume literals before comments so comment markers inside strings are harmless.
    return re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/',
                  " ", source, flags=re.DOTALL)


def active_annotations(source):
    return re.findall(r"@Opcodes\s*\(([^)]*)\)", code_only(source))


def inventory(root):
    constants = {}
    aliases = {}
    for file in sorted((root / "src/main/java/emu/grasscutter").rglob("*.java")):
        source = code_only(file.read_text())
        owner = file.stem
        for name, value in re.findall(r"(?:public\s+)?static\s+final\s+int\s+(\w+)\s*=\s*(-?\d+)\s*;", source):
            constants[f"{owner}.{name}"] = int(value)
        for name, value in re.findall(r"static\s+final\s+int\s+(\w+)\s*=\s*(\w+\.\w+)\s*;", source):
            aliases[f"{owner}.{name}"] = value
    while aliases:
        resolved = [key for key, value in aliases.items() if value in constants]
        if not resolved:
            break
        for key in resolved:
            constants[key] = constants[aliases.pop(key)]
    rows = []
    directory = root / "src/main/java/emu/grasscutter/server/packet/recv"
    for file in sorted(directory.glob("*.java")):
        for annotation in active_annotations(file.read_text()):
            value = annotation.split(",")[0].strip()
            value = re.sub(r"^value\s*=\s*", "", value)
            opcode = int(value) if re.fullmatch(r"-?\d+", value) else constants[value]
            disabled = bool(re.search(r"disabled\s*=\s*true", annotation))
            rows.append({"handler": file.stem, "constant": value, "opcode": opcode,
                         "disabled": disabled,
                         "status": "receive-route-only" if 0 < opcode <= 65535 and not disabled else "deferred"})
    counts = {"active_annotations": len(rows),
              "positive": sum(row["opcode"] > 0 for row in rows),
              "zero": sum(row["opcode"] == 0 for row in rows),
              "negative": sum(row["opcode"] < 0 for row in rows)}
    return {"scope": "Active annotations only. Positive receive routes do not establish response mapping, eligibility, or end-to-end feature support.",
            "counts": counts, "handlers": rows}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps(inventory(args.root), indent=2) + "\n")
