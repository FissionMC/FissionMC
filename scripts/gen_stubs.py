#!/usr/bin/env python3
"""Generate a Java class implementing <interface> with throwing stubs for every abstract method
(including inherited ones). Refuses to overwrite: generated files are hand-edited afterwards.

  gen_stubs.py <interface-fqcn> <out-class-fqcn> [--abstract] [--extends fqcn]

Needs the claude distrobox (javap) and paper-api + adventure jars in ~/.gradle.
"""
import glob, os, re, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVAP = "/usr/lib/jvm/java-25-openjdk/bin/javap"


def classpath():
    jars = glob.glob(os.path.expanduser("~/.gradle/caches/modules-2/files-2.1/io.papermc.paper/paper-api/26.3.build.240-stable/*/paper-api-26.3.build.240-stable.jar"))
    for g in ("net.kyori/adventure-api", "net.kyori/adventure-key", "com.google.guava/guava", "org.jspecify/jspecify"):
        jars += [j for j in glob.glob(os.path.expanduser(f"~/.gradle/caches/modules-2/files-2.1/{g}/*/*/*.jar")) if "sources" not in j]
    jars.append(str(ROOT / ".cache/26.3/x/META-INF/versions/26.3/server-26.3.jar"))
    return ":".join(jars)


def javap(cls):
    cmd = f"{JAVAP} -protected -cp {classpath()} '{cls}'"
    return subprocess.run(["distrobox", "enter", "claude", "--", "bash", "-lc", cmd], capture_output=True, text=True, check=True).stdout


def split_params(params):
    ps, d, cur = [], 0, ""
    for ch in params:
        d += ch == "<"
        d -= ch == ">"
        if ch == "," and d == 0:
            ps.append(cur.strip()); cur = ""
        else:
            cur += ch
    if cur.strip():
        ps.append(cur.strip())
    return ps


def collect(cls, seen, methods):
    if cls in seen:
        return
    seen.add(cls)
    text = javap(cls)
    head = next((l for l in text.splitlines() if re.search(r"\b(interface|class)\b", l) and "{" in l), "")
    for l in text.splitlines():
        l = l.strip()
        if not l.startswith("public abstract") or not l.endswith(";"):
            continue
        sig = re.sub(r"\s+throws\s+.*$", "", l[len("public abstract "):-1]).replace("$", ".")
        m = re.match(r"^(.*?)\s+(\w+)\((.*)\)$", sig)
        if not m:
            continue
        ret, name, params = m.groups()
        ps = split_params(params)
        key = (name, tuple(re.sub(r"<.*>", "", p) for p in ps))
        if key in methods:
            continue
        plist = ", ".join((f"{p[:-3]}... a{i}" if p.endswith("...") else f"{p} a{i}") for i, p in enumerate(ps))
        methods[key] = f'    @Override public {ret} {name}({plist}) {{ throw new UnsupportedOperationException("{cls.split(".")[-1]}#{name}"); }}'
    m = re.search(r"extends\s+(.*?)\s*\{", head)
    if m and "interface" in head:
        for sup in split_params(m.group(1)):
            collect(re.sub(r"<.*>", "", sup).replace(".", "$", 0), seen, methods)


def main():
    iface, out = sys.argv[1], sys.argv[2]
    abstract = "--abstract" in sys.argv
    methods = {}
    collect(iface, set(), methods)
    pkg, name = out.rsplit(".", 1)
    path = ROOT / "craft/src/main/java" / out.replace(".", "/")
    path = path.with_suffix(".java")
    if path.exists():
        sys.exit(f"{path} exists; refusing to overwrite")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        f"package {pkg};\n\n/** Generated skeleton for {iface}; hand-edited from here on. Do not regenerate. */\n"
        f"public {'abstract ' if abstract else ''}class {name} implements {iface.replace('$', '.')} {{\n" + "\n".join(methods.values()) + "\n}\n")
    print(f"{path.relative_to(ROOT)}: {len(methods)} methods")


main()
