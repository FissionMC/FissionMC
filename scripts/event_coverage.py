#!/usr/bin/env python3
"""Which Bukkit/Paper event classes does the port never construct (so they can never fire)?

  event_coverage.py <list of class paths, one per line, from the paper-api jar> [--out FILE]

An event counts as constructed when `new <SimpleName>(` (or `<SimpleName>(` via a factory call such as `.callEvent()` on `new`) appears in craft/ or backend/ sources.
Abstract base events and events plugins construct themselves (custom events, `*Event` bases) show up as false positives: read the list, do not treat it as a bug count.
"""
import re, subprocess, sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
names = [Path(l.strip()) for l in Path(sys.argv[1]).read_text().splitlines() if l.strip()]
src = ""
for base in ("craft/src/main/java", "backend/src/main"):
    for f in (ROOT / base).rglob("*"):
        if f.suffix in (".java", ".kt"):
            src += f.read_text(errors="replace") + "\n"
built = set(re.findall(r"new\s+(?:[\w.]+\.)?(\w+Event)\s*(?:<[^>]*>)?\s*\(", src))
missing = defaultdict(list)
for p in names:
    n = p.stem
    if n not in built:
        missing[p.parent.as_posix().replace("/", ".")].append(n)
# where Paper itself constructs each missing event (patches/sources hunks that a mixin must carry, or paper-server/src files we have not ported)
PAPER = ROOT / ".cache/upstream/paper/paper-server"
paper_src = {}
for base in (PAPER / "patches", PAPER / "src/main/java"):
    for f in base.rglob("*"):
        if f.is_file() and f.suffix in (".patch", ".java"):
            for n in set(re.findall(r"new\s+(?:[\w.]+\.)?(\w+Event)\s*(?:<[^>]*>)?\s*\(", f.read_text(errors="replace"))):
                paper_src.setdefault(n, []).append(f.relative_to(PAPER).as_posix().removeprefix("patches/sources/").removeprefix("src/main/java/"))
out = open(sys.argv[sys.argv.index("--out") + 1], "w") if "--out" in sys.argv else sys.stdout
total = sum(len(v) for v in missing.values())
print(f"# Events never constructed by the port: {total} of {len(names)}\n", file=out)
real = sorted((n, paper_src[n]) for ns in missing.values() for n in ns if n in paper_src)
print(f"## Paper constructs these {len(real)} but the port does not (real gaps; event -> Paper files)\n", file=out)
for n, fs in real:
    print(f"- {n}: {', '.join(sorted(set(fs))[:3])}", file=out)
print("\n## Not constructed by Paper's server code either (abstract bases, API-side events, events fired through other paths): " + str(total - len(real)) + "\n", file=out)
for pkg, ns in sorted(missing.items()):
    print(f"- {pkg}: " + ", ".join(sorted(n for n in ns if n not in paper_src)), file=out)
