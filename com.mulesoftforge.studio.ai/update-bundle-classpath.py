#!/usr/bin/env python3
"""Rewrite Bundle-ClassPath in META-INF/MANIFEST.MF from lib/*.jar (sorted)."""
from pathlib import Path

root = Path(__file__).resolve().parent
manifest = root / "META-INF" / "MANIFEST.MF"
lib = root / "lib"
jars = sorted(p.name for p in lib.glob("*.jar"))
if not jars:
    raise SystemExit(f"No jars in {lib}")

text = manifest.read_text()
lines = text.splitlines()
# Drop existing Bundle-ClassPath continuation lines.
out = []
i = 0
while i < len(lines):
    line = lines[i]
    if line.startswith("Bundle-ClassPath:"):
        out.append("Bundle-ClassPath: .,")
        for j, name in enumerate(jars):
            comma = "," if j < len(jars) - 1 else ""
            out.append(f" lib/{name}{comma}")
        i += 1
        while i < len(lines) and (lines[i].startswith(" ") or lines[i].startswith("\t")):
            i += 1
        continue
    out.append(line)
    i += 1

manifest.write_text("\n".join(out) + "\n")
print(f"Bundle-ClassPath: {len(jars)} jars")
