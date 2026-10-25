#!/usr/bin/env python3
"""Checks that every shared library shipped with the app can find its DT_NEEDED dependencies on a device.

For each ABI, every ELF inside the APK (lib/<abi>/*.so) and inside the bundled runtime archives
(assets/bundled/<abi>/runtimes/*.tar.xz) and component zips (assets/bundled/<abi>/components/*.zip)
must only need:
  * Android NDK system libraries (always present on the device),
  * a library in the same archive, or
  * a library packaged in the APK (loaded/preloaded from nativeLibraryDir).
Runtime libraries may additionally need a library that EVERY LWJGL component provides (one of them is always
loaded next to the runtime at launch, e.g. libfreetype.so for jre8's AWT font manager).

This is what caught "libjvm.so: dlopen failed: library libc++_shared.so not found" for jre25.

Usage: check_bundle_needed.py <apk-or-jniLibs-dir> <assets-dir> [abi ...]
Exit status 1 when an unresolved dependency is found.
"""
import io
import lzma
import os
import struct
import sys
import tarfile
import zipfile

NDK_SYSTEM = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so",
    "libGLESv3.so", "libvulkan.so", "libz.so", "libjnigraphics.so", "libOpenSLES.so", "libOpenMAXAL.so", "libaaudio.so",
    "libmediandk.so", "libnativewindow.so", "libcamera2ndk.so", "libstdc++.so", "libsync.so", "libneuralnetworks.so",
    "libamidi.so", "libbinder_ndk.so", "libicu.so", "libnativehelper.so",
}

ABI_MACHINE = {"arm64-v8a": 183, "armeabi-v7a": 40, "x86_64": 62, "x86": 3}


def elf_needed(data: bytes):
    """Returns (soname, [needed]) from an ELF image, or None if not ELF. Pure python, 32/64-bit, little-endian."""
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return None
    is64 = data[4] == 2
    if data[5] != 1:
        raise ValueError("big-endian ELF not supported")
    if is64:
        e_phoff, = struct.unpack_from("<Q", data, 0x20)
        e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)
    else:
        e_phoff, = struct.unpack_from("<I", data, 0x1C)
        e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x2A)
    machine, = struct.unpack_from("<H", data, 0x12)
    loads, dyn = [], None
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if is64:
            p_type, _flags, p_offset, p_vaddr, _paddr, p_filesz = struct.unpack_from("<IIQQQQ", data, off)
        else:
            p_type, p_offset, p_vaddr, _paddr, p_filesz = struct.unpack_from("<IIIII", data, off)
        if p_type == 1:
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == 2:
            dyn = (p_offset, p_filesz)
    if dyn is None:
        return machine, None, []

    def v2o(vaddr):
        for va, of, sz in loads:
            if va <= vaddr < va + sz:
                return vaddr - va + of
        return None

    entries, strtab = [], None
    step = 16 if is64 else 8
    fmt = "<qQ" if is64 else "<iI"
    for off in range(dyn[0], dyn[0] + dyn[1], step):
        tag, val = struct.unpack_from(fmt, data, off)
        if tag == 0:
            break
        if tag == 5:
            strtab = v2o(val)
        entries.append((tag, val))
    if strtab is None:
        return machine, None, []

    def s(o):
        end = data.index(b"\0", strtab + o)
        return data[strtab + o:end].decode()

    needed = [s(v) for t, v in entries if t == 1]
    soname = next((s(v) for t, v in entries if t == 14), None)
    return machine, soname, needed


def scan_archive(name, members):
    """members: iterable of (path, bytes). Returns dict basename -> (machine, soname, needed)."""
    out = {}
    for path, data in members:
        r = elf_needed(data)
        if r is None:
            continue
        out[os.path.basename(path)] = r
    return out


def tar_members(path):
    with open(path, "rb") as f:
        raw = lzma.decompress(f.read()) if path.endswith(".xz") else f.read()
    with tarfile.open(fileobj=io.BytesIO(raw)) as t:
        for m in t.getmembers():
            if m.isfile() and (".so" in os.path.basename(m.name)):
                yield m.name, t.extractfile(m).read()


def zip_members(path):
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if ".so" in os.path.basename(n) and not n.endswith("/"):
                yield n, z.read(n)


def apk_libs(src, abi):
    if os.path.isdir(src):
        d = os.path.join(src, abi)
        if os.path.isdir(d):
            for n in sorted(os.listdir(d)):
                with open(os.path.join(d, n), "rb") as f:
                    yield n, f.read()
        return
    with zipfile.ZipFile(src) as z:
        for n in z.namelist():
            if n.startswith(f"lib/{abi}/") and n.endswith(".so"):
                yield n, z.read(n)


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    apk, assets = sys.argv[1], sys.argv[2]
    abis = sys.argv[3:] or [a for a in ABI_MACHINE if os.path.isdir(os.path.join(assets, "bundled", a))]
    problems, checked = [], 0
    for abi in abis:
        apk_set = scan_archive("apk", apk_libs(apk, abi))
        apk_names = set(apk_set)
        archives = [("APK lib/" + abi, apk_set)]
        base = os.path.join(assets, "bundled", abi)
        rt_dir, comp_dir = os.path.join(base, "runtimes"), os.path.join(base, "components")
        if os.path.isdir(rt_dir):
            for n in sorted(os.listdir(rt_dir)):
                archives.append((f"{abi}/runtimes/{n}", scan_archive(n, tar_members(os.path.join(rt_dir, n)))))
        if os.path.isdir(comp_dir):
            for n in sorted(os.listdir(comp_dir)):
                if n.endswith(".zip"):
                    archives.append((f"{abi}/components/{n}", scan_archive(n, zip_members(os.path.join(comp_dir, n)))))
        lwjgl = [libs for label, libs in archives if "/components/lwjgl" in label]
        always_with_runtime = set.intersection(*[set(l) for l in lwjgl]) if lwjgl else set()
        for label, libs in archives:
            local = set(libs) | {r[1] for r in libs.values() if r[1]}
            if "/runtimes/" in label:
                local |= always_with_runtime
            for lib, (machine, _soname, needed) in sorted(libs.items()):
                checked += 1
                if machine != ABI_MACHINE[abi]:
                    problems.append(f"{label}: {lib} has ELF machine {machine}, expected {ABI_MACHINE[abi]} for {abi}")
                for dep in needed:
                    if dep in NDK_SYSTEM or dep in local or dep in apk_names:
                        continue
                    problems.append(f"{label}: {lib} needs {dep}, which is neither a system library, in the same archive, nor in the APK")
        print(f"[{abi}] {sum(len(l) for _, l in archives)} ELF files in {len(archives)} archives; APK libs: {', '.join(sorted(apk_names)) or '-'}")
    if problems:
        print("\nUnresolved native dependencies:")
        for p in problems:
            print("  " + p)
        return 1
    print(f"OK: {checked} shared libraries, every DT_NEEDED resolves")
    return 0


if __name__ == "__main__":
    sys.exit(main())
