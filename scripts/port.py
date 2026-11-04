#!/usr/bin/env python3
"""Port source files from the local Paper clone into FissionMC, recording provenance.

  port.py paper <path-under-paper-server/src/main/java> [...]   file or directory (package path)
  port.py paper --resources <path-under-paper-server/src/main/resources> [...]
  port.py paper --generated <path-under-paper-server/src/generated/java> [...]
  port.py newfile <patch path under paper-server/patches/sources> [...]   files Paper creates in a patch
  port.py status                                                ported files whose upstream changed / that we edited

Destination is craft/src/main/java (or resources). `upstream/ported/paper.tsv` records
dest, upstream path, upstream commit, sha256 of the file as copied. A file whose current
sha differs from the recorded one has local edits; `status` shows them against upstream.
Clone lives in .cache/upstream/paper (git clone --depth 1; refresh with git pull).
"""
import hashlib, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLONE = ROOT / ".cache/upstream/paper"
MANIFEST = ROOT / "upstream/ported/paper.tsv"
MODULE = ROOT / "craft"
SKIPPED = ROOT / "upstream/ported/skipped.txt"  # "<dest path>\t<reason>" — deliberately not ported


def skipped():
    return {l.split("\t")[0] for l in SKIPPED.read_text().splitlines() if l.strip() and not l.startswith("#")} if SKIPPED.exists() else set()


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def commit() -> str:
    return subprocess.run(["git", "rev-parse", "HEAD"], cwd=CLONE, capture_output=True, text=True, check=True).stdout.strip()


def load():
    rows = {}
    if MANIFEST.exists():
        for line in MANIFEST.read_text().splitlines():
            d, s, c, h = line.split("\t")
            rows[d] = (s, c, h)
    return rows


def save(rows):
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text("".join(f"{d}\t{s}\t{c}\t{h}\n" for d, (s, c, h) in sorted(rows.items())))


ROOTS = {"java": ("paper-server/src/main/java", "src/main/java"),
         "generated": ("paper-server/src/generated/java", "src/main/java"),
         "resources": ("paper-server/src/main/resources", "src/main/resources")}


def port(args, root="java"):
    base, dest_rel = ROOTS[root]
    dest_base = MODULE / dest_rel
    rows, c, n, skip = load(), commit(), 0, skipped()
    for a in args:
        src = CLONE / base / a
        files = [src] if src.is_file() else sorted(f for f in src.rglob("*") if f.is_file())
        if not files:
            sys.exit(f"nothing at {src}")
        for f in files:
            rel = f.relative_to(CLONE / base)
            dest = dest_base / rel
            key = str(dest.relative_to(ROOT))
            if key in skip:
                continue
            if dest.exists() and key not in rows:
                print("SKIP (untracked local file, e.g. a shim):", key)
                continue
            if dest.exists() and sha(dest) != rows[key][2]:
                print("SKIP (locally edited):", key)
                continue
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(f.read_bytes())
            rows[str(dest.relative_to(ROOT))] = (str(f.relative_to(CLONE)), c, sha(dest))
            n += 1
    save(rows)
    print(f"ported {n} files at {c[:10]}")


def newfile(patches):
    """Extract files that Paper creates through a source patch (--- /dev/null)."""
    rows, c = load(), commit()
    for rel in patches:
        p = CLONE / "paper-server/patches/sources" / rel
        lines = p.read_text().splitlines()
        assert lines[0].startswith("--- /dev/null"), f"{rel} does not create a file"
        target = lines[1].split(None, 1)[1].removeprefix("b/")
        body = [l[1:] for l in lines[2:] if l.startswith("+")]
        dest = MODULE / "src/main/java" / target
        key = str(dest.relative_to(ROOT))
        if dest.exists() and (key not in rows or sha(dest) != rows[key][2]):
            print("SKIP (local file or edited):", key)
            continue
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text("\n".join(body) + "\n")
        rows[key] = (str(p.relative_to(CLONE)), c, sha(dest))
        print("created", key)
    save(rows)


def status():
    rows = load()
    head = commit()
    for d, (s, c, h) in sorted(rows.items()):
        p = ROOT / d
        edited = (not p.exists()) or sha(p) != h
        changed = subprocess.run(["git", "diff", "--quiet", c, "HEAD", "--", s], cwd=CLONE).returncode != 0 if c != head else False
        if edited or changed:
            print(f"{'edited ' if edited else '       '}{'upstream-changed' if changed else ''}\t{d}")


if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["status"]:
        status()
    elif a[:1] == ["newfile"]:
        newfile(a[1:])
    elif a[:1] == ["paper"]:
        root = "resources" if "--resources" in a else "generated" if "--generated" in a else "java"
        port([x for x in a[1:] if not x.startswith("--")], root)
    else:
        sys.exit(__doc__)
