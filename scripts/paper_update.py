#!/usr/bin/env python3
"""Move the Paper reference (.cache/upstream/paper) to a newer commit and update what we ported from it.

  paper_update.py fetch [--depth N]   git fetch origin main into the clone (NETWORK; the only command that needs it)
  paper_update.py plan [TARGET]       report only (default TARGET = origin/main, must already be in the clone)
  paper_update.py apply [TARGET]      apply clean updates, 3-way merge edited files, write conflicts to the report, move the pin

What it does (provenance = upstream/ported/paper.tsv: dest, upstream path, commit, sha256 as copied):
  * for every ported file whose upstream content changed between its recorded commit and TARGET:
      - not locally edited (sha(dest) == recorded)  -> copied from TARGET, row updated ("clean")
      - locally edited                              -> `git merge-file` (base = upstream at the recorded commit, ours = dest, theirs = TARGET).
                                                       Clean merge -> written (row = TARGET's sha, so the file still shows as edited);
                                                       conflicts   -> dest untouched, listed in the report ("conflict")
      - deleted upstream                            -> listed ("removed upstream"), file kept
  * `paper-server/patches/sources/**.patch` that changed (these carry the hunks our mixins implement) are listed with the mixin files that
    implement them (by class name) and the number of TODO(behaviour) markers: this is the work list for the mixins, never applied automatically.
  * `patches/features/*` added/changed/removed are listed.
  * refuses a TARGET whose gradle.properties mcVersion differs from ours (the pinned Minecraft version 26.3 is never changed here).
  * `apply` moves upstream/upstreams.toml `[paper] pinned` to TARGET and checks the clone out at TARGET. Report: .dev/paper-update-report.md.
Run `git status` afterwards; build; commit with explicit paths.
"""
import hashlib, os, re, subprocess, sys, tempfile
from pathlib import Path

ROOT = Path(os.environ.get("PAPER_UPDATE_ROOT") or Path(__file__).resolve().parent.parent)   # override: tests on a scratch tree
CLONE = ROOT / ".cache/upstream/paper"
MANIFEST = ROOT / "upstream/ported/paper.tsv"
REG = ROOT / "upstream/upstreams.toml"
MIXINS = ROOT / "backend/src/main/java/net/fissionmc/backend/mixin"
REPORT = ROOT / ".dev/paper-update-report.md"


def git(*a, check=True, text=True):
    return subprocess.run(["git", *a], cwd=CLONE, capture_output=True, text=text, check=check)


def show(commit, path):
    r = git("show", f"{commit}:{path}", check=False, text=False)
    return r.stdout if r.returncode == 0 else None


def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def rev(x):
    return git("rev-parse", x).stdout.strip()


def load():
    rows = {}
    for line in MANIFEST.read_text().splitlines():
        d, s, c, h = line.split("\t")
        rows[d] = [s, c, h]
    return rows


def save(rows):
    MANIFEST.write_text("".join(f"{d}\t{s}\t{c}\t{h}\n" for d, (s, c, h) in sorted(rows.items())))


def mc_version(commit):
    t = (show(commit, "gradle.properties") or b"").decode()
    m = re.search(r"^mcVersion=(.+)$", t, re.M)
    return m.group(1).strip() if m else None


def our_mc():
    return re.search(r"^mcVersion=(.+)$", (ROOT / "gradle.properties").read_text(), re.M).group(1).strip()


def mixin_for(patch):
    cls = Path(patch).name.removesuffix(".java.patch")
    files = list(MIXINS.rglob(cls + "Mixin*.java"))
    return files


def analyse(target):
    rows = load()
    out = dict(clean=[], merged=[], conflict=[], removed=[], same=0, merges={})
    for d, (s, c, h) in sorted(rows.items()):
        theirs = show(target, s)
        base = show(c, s)
        dest = ROOT / d
        if theirs is None:
            out["removed"].append(d)
            continue
        if base is not None and base == theirs:
            out["same"] += 1
            continue
        edited = dest.exists() and sha(dest.read_bytes()) != h
        if not edited:
            out["clean"].append((d, s, theirs))
            continue
        if base is None:
            out["conflict"].append((d, "no base version of the recorded commit in the clone"))
            continue
        with tempfile.TemporaryDirectory() as t:
            tp = Path(t)
            (tp / "ours").write_bytes(dest.read_bytes()); (tp / "base").write_bytes(base); (tp / "theirs").write_bytes(theirs)
            r = subprocess.run(["git", "merge-file", "-p", str(tp / "ours"), str(tp / "base"), str(tp / "theirs")], capture_output=True)
            if r.returncode == 0:
                out["merged"].append((d, s, theirs, r.stdout))
            else:
                out["conflict"].append((d, f"{r.returncode} conflict hunk(s)" if r.returncode > 0 else "merge-file failed"))
    return out


def patch_changes(old, target):
    r = git("diff", "--name-status", old, target, "--", "paper-server/patches/sources", "paper-server/patches/features", check=False)
    return [l.split("\t") for l in r.stdout.splitlines() if l.strip()]


def report(target, a, patches, applied, old):
    lines = [f"# Paper update report ({'applied' if applied else 'plan'})", "", f"target {target[:10]}, pin was {old[:10]}, mcVersion {mc_version(target)} (ours {our_mc()})", "",
             f"ported files: unchanged upstream {a['same']}, clean updates {len(a['clean'])}, merged (had local edits) {len(a['merged'])}, CONFLICTS {len(a['conflict'])}, removed upstream {len(a['removed'])}", ""]
    for title, items in (("Clean updates", [x[0] for x in a["clean"]]), ("Merged with local edits (re-check)", [x[0] for x in a["merged"]]),
                         ("CONFLICTS (dest untouched, re-port by hand: recorded commit vs target diff via `git -C .cache/upstream/paper diff`)", [f"{d}: {why}" for d, why in a["conflict"]]),
                         ("Removed upstream (file kept)", a["removed"])):
        if items:
            lines += [f"## {title}", *[f"- {i}" for i in items], ""]
    src = [p for p in patches if "/sources/" in p[-1]]
    feat = [p for p in patches if "/features/" in p[-1]]
    if src:
        lines += ["## Changed source patches (mixin work list)", "| status | patch | mixin files | TODO(behaviour) |", "|---|---|---|---|"]
        for p in src:
            st, path = p[0], p[-1]
            ms = mixin_for(path)
            lines.append(f"| {st} | {path.removeprefix('paper-server/patches/sources/')} | {', '.join(m.name for m in ms) or '-'} | {sum(m.read_text(errors='replace').count('TODO(behaviour)') for m in ms)} |")
        lines.append("")
    if feat:
        lines += ["## Changed feature patches", *[f"- {p[0]} {p[-1].removeprefix('paper-server/patches/features/')}" for p in feat], ""]
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text("\n".join(lines) + "\n")
    print("\n".join(lines[:8]))
    print(f"report: {REPORT}")


def pin():
    return re.search(r"\[paper\][^\[]*?pinned = \"([^\"]*)\"", REG.read_text(), re.S).group(1)


def set_pin(sha_):
    t = REG.read_text()
    n, k = re.subn(r"(\[paper\][^\[]*?pinned = \")[^\"]*(\")", rf"\g<1>{sha_}\g<2>", t, count=1, flags=re.S)
    assert k == 1
    REG.write_text(n)


def cmd(mode, target_arg=None):
    target = rev(target_arg or "origin/main")
    if mc_version(target) != our_mc():
        sys.exit(f"REFUSED: target {target[:10]} has mcVersion {mc_version(target)}, we are pinned to {our_mc()}")
    old = pin()
    a = analyse(target)
    patches = patch_changes(pin(), target) if show(pin(), "gradle.properties") is not None else []
    if mode == "apply":
        rows = load()
        for d, s, theirs in a["clean"]:
            (ROOT / d).write_bytes(theirs); rows[d] = [s, target, sha(theirs)]
        for d, s, theirs, merged in a["merged"]:
            (ROOT / d).write_bytes(merged); rows[d] = [s, target, sha(theirs)]   # recorded sha = the upstream copy, so the file still reads as locally edited
        for d, (s, c, h) in list(rows.items()):   # unchanged and conflicting rows keep their old commit
            pass
        save(rows)
        set_pin(target)
        git("checkout", "-q", target)
    report(target, a, patches, mode == "apply", old)


if __name__ == "__main__":
    a = sys.argv[1:]
    if not a or a[0] not in ("fetch", "plan", "apply"):
        sys.exit(__doc__)
    if a[0] == "fetch":
        depth = a[a.index("--depth") + 1] if "--depth" in a else "50"
        print(git("fetch", f"--depth={depth}", "origin", "main").stderr)
    else:
        cmd(a[0], a[1] if len(a) > 1 else None)
