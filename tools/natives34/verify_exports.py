#!/usr/bin/env python3
"""Verifies that natives built for Android satisfy the LWJGL jars they ship with.

Two failure modes are caught before anything reaches a device:
  * LWJGL binds library entry points with APIUtil.apiGetFunctionAddress, which throws during class initialisation when
    a symbol is missing (so one absent function breaks the whole binding class).
  * JNI methods are bound lazily; a missing one only fails when first called, as UnsatisfiedLinkError.

  verify_exports.py --jars DIR --lib DIR --nm llvm-nm [--extra-lib-map pkg=lib.so ...] [--sdl-lib path]
"""
import argparse, os, re, subprocess, sys, zipfile

# binding package -> shared library that must export its required functions (dynamic, non-JNI bindings)
DYNAMIC = {
    "org/lwjgl/util/shaderc/": "libshaderc.so",
    "org/lwjgl/util/spvc/": "libspirv-cross.so",
    "org/lwjgl/util/freetype/": "libfreetype.so",
    "org/lwjgl/openal/": "libopenal.so",   # entry points resolved through alcGetProcAddress; checks presence + core
}
# Entry points resolved directly (not via apiGetFunctionAddress) that the library must still export.
EXTRA_REQUIRED = {
    "libopenal.so": {"alcGetProcAddress", "alGetProcAddress", "alcOpenDevice", "alcCreateContext", "alcMakeContextCurrent",
                     "alcGetString", "alGetError", "alcIsExtensionPresent"},
}
# Bindings deliberately not built for Android: io_uring ( needs kernel/libc support apps cannot rely on; nothing in
# Minecraft or LWJGL's core paths touches it). Their JNI methods are reported, not failed.
EXCLUDED_JNI = ("org/lwjgl/system/linux/liburing/",
                # other operating systems' bindings (never loaded on Linux/Android)
                "org/lwjgl/system/windows/", "org/lwjgl/system/macosx/", "org/lwjgl/system/freebsd/",
                "org/lwjgl/opengl/WGL", "org/lwjgl/opengl/CGL")
# package -> library that implements its JNI methods
JNI = {
    "org/lwjgl/system/": "liblwjgl.so",
    "org/lwjgl/opengl/": "liblwjgl_opengl.so",
    "org/lwjgl/stb/": "liblwjgl_stb.so",
    "org/lwjgl/util/tinyfd/": "liblwjgl_tinyfd.so",
}


def exports(nm, lib):
    out = subprocess.run([nm, "-D", "--defined-only", lib], capture_output=True, text=True, check=True).stdout
    return {line.split()[-1].split("@")[0] for line in out.splitlines() if line.strip()}


def classes(jar, prefixes):
    with zipfile.ZipFile(jar) as z:
        return sorted(n[:-6] for n in z.namelist()
                      if n.endswith(".class") and not n.startswith("META-INF/") and any(n.startswith(p) for p in prefixes))


def javap(jar, names, flags):
    res = []
    for i in range(0, len(names), 150):
        r = subprocess.run(["javap", *flags, "-cp", jar, *[n.replace("/", ".") for n in names[i:i + 150]]],
                           capture_output=True, text=True)
        res.append(r.stdout)
    return "\n".join(res)


def required_functions(text):
    req, pend = set(), None
    for line in text.splitlines():
        m = re.search(r"// String ([A-Za-z_][A-Za-z0-9_]*)$", line)
        if m:
            pend = m.group(1)
            continue
        if pend and "apiGetFunctionAddress" in line:
            if "Optional" not in line:
                req.add(pend)
            pend = None
        elif pend and ("invoke" in line or "putstatic" in line):
            pend = None
    return req


def jni_mangle(s):
    out = []
    for ch in s:
        if ch == "/" or ch == ".": out.append("_")
        elif ch == "_": out.append("_1")
        elif ch == ";": out.append("_2")
        elif ch == "[": out.append("_3")
        elif ch.isascii() and ch.isalnum(): out.append(ch)
        else: out.append("_0%04x" % ord(ch))
    return "".join(out)


def native_methods(text):
    cur, found = None, []
    for line in text.splitlines():
        m = re.match(r"^(?:public |final |abstract |\s)*(?:class|interface|enum) ([\w.$]+)", line.strip())
        if m and ("class " in line or "interface " in line):
            cur = m.group(1).split("<")[0]
            continue
        if cur and " native " in f" {line.strip()} ":
            m2 = re.search(r"\s([\w$]+)\(", line)
            if m2:
                found.append((cur, m2.group(1)))
    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jars", required=True)
    ap.add_argument("--lib", required=True)
    ap.add_argument("--nm", required=True)
    a = ap.parse_args()
    jars = [os.path.join(a.jars, j) for j in sorted(os.listdir(a.jars)) if j.endswith(".jar")]
    problems, checked = [], 0
    cache = {}

    def lib_exports(name):
        if name not in cache:
            p = os.path.join(a.lib, name)
            cache[name] = exports(a.nm, p) if os.path.exists(p) else None
        return cache[name]

    for jar in jars:
        for pkg, lib in DYNAMIC.items():
            names = classes(jar, [pkg])
            if not names:
                continue
            req = required_functions(javap(jar, names, ["-c", "-p"])) | EXTRA_REQUIRED.get(lib, set())
            ex = lib_exports(lib)
            if ex is None:
                problems.append(f"{lib} missing (needed by {pkg})"); continue
            miss = sorted(req - ex)
            checked += len(req)
            print(f"{lib}: {len(req)} required functions from {os.path.basename(jar)}, {len(miss)} missing")
            problems += [f"{lib} lacks {m}" for m in miss]
        for pkg, lib in JNI.items():
            names = classes(jar, [pkg])
            if not names:
                continue
            nat = native_methods(javap(jar, names, ["-p"]))
            nat = [(c, m) for c, m in nat if c.replace(".", "/").startswith(pkg)]
            skipped = [x for x in nat if x[0].replace(".", "/").startswith(EXCLUDED_JNI)]
            if skipped:
                print(f"{lib}: {len(skipped)} JNI methods of excluded bindings skipped ({', '.join(EXCLUDED_JNI)})")
            nat = [x for x in nat if x not in skipped]
            if not nat:
                continue
            ex = lib_exports(lib)
            if ex is None:
                problems.append(f"{lib} missing (needed by {pkg}, {len(nat)} native methods)"); continue
            miss = []
            for c, m in nat:
                short = "Java_" + jni_mangle(c.replace(".", "/")) + "_" + jni_mangle(m)
                if short not in ex and not any(e.startswith(short + "__") for e in ex):
                    miss.append(short)
            checked += len(nat)
            print(f"{lib}: {len(nat)} JNI methods from {os.path.basename(jar)}, {len(miss)} missing")
            problems += [f"{lib} lacks {m}" for m in miss]
    print(f"checked {checked} symbols; {len(problems)} problems")
    for p in problems[:200]:
        print("  " + p)
    sys.exit(1 if problems or checked == 0 else 0)


if __name__ == "__main__":
    main()
