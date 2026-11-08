#!/usr/bin/env python3
"""Inventory of what is not yet ported from Paper, ranked by plugin impact.

  paper_inventory.py [--out FILE]     write a markdown report (default stdout)

Sources: .dev/docs/port/*.md checklist lines (`- [ ]/[~]/[x] sources/<path>.patch (...)`),
paper-server/patches/sources/** in the Paper clone, backend mixins (`TODO(behaviour)`),
craft stubs (UnsupportedOperationException added by us, i.e. count above upstream's),
paper-server/patches/features/*. Ranking: patches whose added lines fire Bukkit events
(callEvent / CraftEventFactory) first, then API surface (org.bukkit / io.papermc.paper
touching), then the rest; unfinished before finished.
"""
import re, subprocess, sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLONE = ROOT / ".cache/upstream/paper/paper-server"
MIXINS = ROOT / "backend/src/main/java/net/fissionmc/backend/mixin"
PORTDOCS = ROOT / ".dev/docs/port"
MANIFEST = ROOT / "upstream/ported/paper.tsv"

CHECK = re.compile(r"^- \[(.)\] (?:sources/)?(\S+\.patch)\s*(.*)$")


def checklist():
    out = {}
    for md in sorted(PORTDOCS.glob("*.md")):
        for line in md.read_text().splitlines():
            m = CHECK.match(line)
            if m:
                out["sources/" + m.group(2)] = (m.group(1), md.stem, m.group(3))
    return out


def mixin_index():
    idx = defaultdict(list)
    for f in MIXINS.rglob("*Mixin.java"):
        idx[f.stem].append(f)
    return idx


def patch_info(rel):
    p = CLONE / "patches" / rel
    added = events = hunks = 0
    creates = False
    for i, l in enumerate(p.read_text(errors="replace").splitlines()):
        if i == 0 and l.startswith("--- /dev/null"):
            creates = True
        if l.startswith("@@"):
            hunks += 1
        elif l.startswith("+") and not l.startswith("+++"):
            added += 1
            if "callEvent" in l or "CraftEventFactory" in l or "Event(" in l and "new " in l:
                events += 1
    return added, events, hunks, creates


def todo_count(files):
    return sum(f.read_text(errors="replace").count("TODO(behaviour)") for f in files)


def stub_delta():
    rows = {}
    if MANIFEST.exists():
        for l in MANIFEST.read_text().splitlines():
            d, s, c, h = l.split("\t")
            rows[d] = s
    res = []
    for f in (ROOT / "craft/src/main/java").rglob("*.java"):
        cur = f.read_text(errors="replace").count("UnsupportedOperationException")
        if not cur:
            continue
        key = str(f.relative_to(ROOT))
        base = 0
        if key in rows:
            up = ROOT / ".cache/upstream/paper" / rows[key]
            base = up.read_text(errors="replace").count("UnsupportedOperationException") if up.exists() else 0
        if cur > base:
            res.append((cur - base, key))
    return sorted(res, reverse=True)


def area_of(path):
    s = path.removeprefix("sources/")
    for k, a in (("net/minecraft/world/entity/player", "player"), ("net/minecraft/server/network", "player"),
                 ("net/minecraft/network", "network"), ("net/minecraft/world/entity", "entity"),
                 ("net/minecraft/world/level/block", "blocks"), ("net/minecraft/world/item", "items"),
                 ("net/minecraft/world/inventory", "inventory"), ("net/minecraft/world/level", "world"),
                 ("net/minecraft/server/level", "world"), ("net/minecraft/server", "server"),
                 ("net/minecraft/commands", "commands"), ("net/minecraft", "minecraft-other"),
                 ("ca/spottedleaf", "moonrise"), ("io/papermc", "paper-misc"), ("com/mojang", "libs")):
        if s.startswith(k):
            return a
    return "other"


def main():
    out = sys.stdout
    if "--out" in sys.argv:
        out = open(sys.argv[sys.argv.index("--out") + 1], "w")
    P = lambda *a: print(*a, file=out)
    cl = checklist()
    allp = {p.relative_to(CLONE / "patches").as_posix() for p in (CLONE / "patches/sources").rglob("*.patch")}
    for k in list(cl):
        if k not in allp:
            for cand in ("sources/net/minecraft/" + k.removeprefix("sources/"), "sources/net/minecraft/world/" + k.removeprefix("sources/")):
                if cand in allp:
                    cl[cand] = cl.pop(k)
                    break
    mix = mixin_index()
    patches = sorted(p.relative_to(CLONE / "patches").as_posix() for p in (CLONE / "patches/sources").rglob("*.patch"))
    rows = []
    for rel in patches:
        st, doc, note = cl.get(rel, (" ", "(not in a checklist)", ""))
        added, events, hunks, creates = patch_info(rel)
        cls = Path(rel).name.removesuffix(".java.patch")
        mfiles = mix.get(cls + "Mixin", [])
        todo = todo_count(mfiles)
        rows.append(dict(rel=rel, st=st, doc=doc, added=added, events=events, hunks=hunks, creates=creates,
                         mixin=bool(mfiles), todo=todo, area=area_of(rel)))
    unf = [r for r in rows if r["st"] != "x"]
    P("# Paper port inventory (generated by scripts/paper_inventory.py)\n")
    c = Counter(r["st"] for r in rows)
    P(f"patches under sources/: {len(rows)}; done [x] {c['x']}, partial [~] {c['~']}, open [ ] {c[' ']}\n")
    P("## By area (unfinished patches / total, event-firing unfinished)\n")
    P("| area | total | open | partial | done | event-hunk patches unfinished | TODO(behaviour) in their mixins |")
    P("|---|---|---|---|---|---|---|")
    by = defaultdict(list)
    for r in rows:
        by[r["area"]].append(r)
    for a, rs in sorted(by.items(), key=lambda kv: -sum(1 for r in kv[1] if r["st"] != "x")):
        P(f"| {a} | {len(rs)} | {sum(r['st']==' ' for r in rs)} | {sum(r['st']=='~' for r in rs)} | {sum(r['st']=='x' for r in rs)} "
          f"| {sum(1 for r in rs if r['st']!='x' and r['events'])} | {sum(r['todo'] for r in rs)} |")
    P("\n## Ranked unfinished patches (events first)\n")
    P("| rank | st | events | added | patch | doc | mixin | TODOs |")
    P("|---|---|---|---|---|---|---|---|")
    api = lambda r: any(k in r["rel"] for k in ("org/bukkit", "io/papermc", "craftbukkit"))
    key = lambda r: (-(r["events"] > 0), -r["events"], 0 if r["st"] == " " else 1, -r["added"])
    for i, r in enumerate(sorted(unf, key=key)[:150], 1):
        P(f"| {i} | {r['st']} | {r['events']} | {r['added']} | {r['rel'].removeprefix('sources/')} | {r['doc']} | {'y' if r['mixin'] else '-'} | {r['todo']} |")
    P(f"\n(showing 150 of {len(unf)} unfinished)\n")
    P("## TODO(behaviour) by mixin directory\n")
    ct = Counter()
    for f in MIXINS.rglob("*.java"):
        n = f.read_text(errors="replace").count("TODO(behaviour)")
        if n:
            ct[f.parent.relative_to(MIXINS).as_posix()] += n
    for d, n in ct.most_common():
        P(f"- {d}: {n}")
    P(f"\ntotal {sum(ct.values())}\n")
    P("## Top mixins by TODO(behaviour)\n")
    per = [(f.read_text(errors="replace").count("TODO(behaviour)"), f.name) for f in MIXINS.rglob("*.java")]
    for n, name in sorted(per, reverse=True)[:25]:
        if n:
            P(f"- {name}: {n}")
    P("\n## Craft stubs that throw UnsupportedOperationException (count above upstream's own)\n")
    sd = stub_delta()
    P(f"{len(sd)} files\n")
    for n, k in sd:
        P(f"- {n} {k.removeprefix('craft/src/main/java/')}")
    P("\n## Paper patches/features/*\n")
    for p in sorted((CLONE / "patches/features").glob("*.patch")):
        P(f"- {p.name}")
    P("\nStatus of features: none ported unless listed in .dev/paper-backlog.md section E.")


main()
