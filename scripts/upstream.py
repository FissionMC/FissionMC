#!/usr/bin/env python3
"""Upstream tracker for FissionMC.

  upstream.py status            show pinned vs remote head for every upstream
  upstream.py update <name>     vendor upstreams: re-copy sources at remote head, reapply patches, bump pin
  upstream.py pin <name> [sha]  reference upstreams: record the commit our ported code is based on
  upstream.py log <name>        commits between pinned and remote head (needs a clone; shallow-deepens)
  upstream.py ported <path>     (reference) list upstream commits touching a Paper path since the pin
"""
import re, shutil, subprocess, sys, tempfile, tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REG = ROOT / "upstream" / "upstreams.toml"


def sh(*a, cwd=None, check=True):
    return subprocess.run(a, cwd=cwd, check=check, capture_output=True, text=True).stdout.strip()


def load():
    return tomllib.loads(REG.read_text())


def head(u):
    ref = "HEAD" if u["branch"] == "HEAD" else f"refs/heads/{u['branch']}"
    out = sh("git", "ls-remote", u["repo"], ref)
    return out.split()[0] if out else None


def set_pin(name, sha):
    text = REG.read_text()
    pat = re.compile(rf'(\[{re.escape(name)}\][^\[]*?pinned = ")[^"]*(")', re.S)
    new, n = pat.subn(rf"\g<1>{sha}\g<2>", text, count=1)
    assert n == 1, f"pin line for {name} not found"
    REG.write_text(new)


def cmd_status():
    for name, u in load().items():
        h = head(u) or "unreachable"
        pin = u["pinned"] or "(unpinned)"
        state = "up to date" if h == u["pinned"] else "CHANGED" if u["pinned"] else "unpinned"
        print(f"{name:20} {u['type']:9} {pin[:10]:12} -> {h[:10]:12} {state}")


def clone(u, depth=1):
    tmp = Path(tempfile.mkdtemp(prefix="upstream-"))
    args = ["git", "clone", "-q", f"--depth={depth}"]
    if u["branch"] != "HEAD":
        args += ["-b", u["branch"]]
    sh(*args, u["repo"], str(tmp / "src"))
    return tmp, tmp / "src"


def cmd_update(name):
    u = load()[name]
    assert u["type"] == "vendor", f"{name} is a reference upstream; use pin"
    tmp, src = clone(u)
    try:
        sha = sh("git", "rev-parse", "HEAD", cwd=src)
        for s, d in u["paths"].items():
            dest = ROOT / d
            if dest.is_dir():
                shutil.rmtree(dest)
            dest.parent.mkdir(parents=True, exist_ok=True)
            (shutil.copytree(src / s, dest, ignore=shutil.ignore_patterns(".git")) if (src / s).is_dir()
             else shutil.copy2(src / s, dest))
        pdir = ROOT / "upstream" / "patches" / name
        for p in sorted(pdir.glob("*.patch")) if pdir.is_dir() else []:
            r = subprocess.run(["git", "apply", "--whitespace=nowarn", str(p)], cwd=ROOT, capture_output=True, text=True)
            if r.returncode:
                sys.exit(f"patch {p.name} no longer applies to {sha[:10]}:\n{r.stderr}")
            print("applied", p.name)
        set_pin(name, sha)
        print(f"{name} vendored at {sha[:10]}")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def cmd_pin(name, sha=None):
    u = load()[name]
    set_pin(name, sha or head(u))
    print(f"{name} pinned")


def cmd_log(name):
    u = load()[name]
    if not u["pinned"]:
        sys.exit("unpinned")
    tmp, src = clone(u, depth=500)
    try:
        print(sh("git", "log", "--oneline", f"{u['pinned']}..HEAD", cwd=src, check=False) or "(none within 500 commits)")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def cmd_ported(path):
    u = load()["paper"]
    tmp, src = clone(u, depth=2000)
    try:
        rng = f"{u['pinned']}..HEAD" if u["pinned"] else "HEAD~50..HEAD"
        print(sh("git", "log", "--oneline", rng, "--", path, cwd=src, check=False) or "(no upstream changes)")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    a = sys.argv[1:]
    if not a:
        sys.exit(__doc__)
    {"status": cmd_status, "update": cmd_update, "pin": cmd_pin, "log": cmd_log, "ported": cmd_ported}[a[0]](*a[1:])
