#!/usr/bin/env python3
"""Packages staged native builds as CS Launcher Pro component packs and maintains bundled/<abi>/index.json.

  --component "id|name|version|kind|license|source|provides(comma)|stageDir"   (stageDir contains lib/ and optional jars/)
  --runtime   "id|major|version|relativeFile|source"                           (file already copied into the bundle)

Every file in a pack is listed with its SHA-256 and size in cs-component.json; the launcher refuses packs whose
contents do not match (see core/component/Components.kt).
"""
import argparse, hashlib, json, os, zipfile


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_index(out, abi):
    p = os.path.join(out, "index.json")
    if os.path.exists(p):
        with open(p) as f:
            return json.load(f)
    return {"formatVersion": 1, "abi": abi, "runtimes": [], "components": []}


def save_index(out, idx):
    with open(os.path.join(out, "index.json"), "w") as f:
        json.dump(idx, f, indent=2, sort_keys=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--abi", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--rev", default="1")
    ap.add_argument("--component", action="append", default=[])
    ap.add_argument("--runtime", action="append", default=[])
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    idx = load_index(a.out, a.abi)

    for spec in a.component:
        cid, name, version, kind, lic, source, provides, stage = spec.split("|")
        version = f"{version}-r{a.rev}"
        files, jars = [], []
        for sub in ("lib", "jars"):
            d = os.path.join(stage, sub)
            if not os.path.isdir(d):
                continue
            for fn in sorted(os.listdir(d)):
                p = os.path.join(d, fn)
                if os.path.isfile(p):
                    rel = f"{sub}/{fn}"
                    files.append({"path": rel, "sha256": sha256(p), "size": os.path.getsize(p)})
                    if sub == "jars":
                        jars.append(rel)
        if not files:
            raise SystemExit(f"component {cid}: nothing staged in {stage}")
        manifest = {"formatVersion": 1, "id": cid, "name": name, "version": version, "kind": kind, "abi": a.abi,
                    "license": lic, "source": source, "provides": [p for p in provides.split(",") if p],
                    "classpathJars": jars, "libraryDir": "lib", "files": files}
        rel_zip = f"components/{cid}.zip"
        zpath = os.path.join(a.out, rel_zip)
        os.makedirs(os.path.dirname(zpath), exist_ok=True)
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("cs-component.json", json.dumps(manifest, indent=2))
            for f in files:
                z.write(os.path.join(stage, f["path"]), f["path"])
        idx["components"] = [c for c in idx["components"] if c["id"] != cid] + [
            {"id": cid, "version": version, "file": rel_zip, "sha256": sha256(zpath), "size": os.path.getsize(zpath)}]
        print(f"packed {cid} {version}: {len(files)} files, {os.path.getsize(zpath)} bytes")

    for spec in a.runtime:
        rid, major, version, rel, source = spec.split("|")
        p = os.path.join(a.out, rel)
        idx["runtimes"] = [r for r in idx["runtimes"] if r["id"] != rid] + [
            {"id": rid, "major": int(major), "version": version, "file": rel, "sha256": sha256(p),
             "size": os.path.getsize(p), "source": source}]
        print(f"bundled runtime {rid} ({version}), {os.path.getsize(p)} bytes")

    save_index(a.out, idx)


if __name__ == "__main__":
    main()
