#!/usr/bin/env python3
"""Packs the offline read-aloud voice for the app to download on demand (see app/.../tts/OfflineModel.kt).

Usage: python3 -I scripts/pack-offline-voice.py LIB_ARCHIVE MODEL_ARCHIVE offline-voice

  LIB_ARCHIVE    sherpa-onnx-v<ver>-android-static-link-onnxruntime.tar.bz2  (k2-fsa/sherpa-onnx releases; one jni .so per ABI)
  MODEL_ARCHIVE  vits-piper-zh_CN-xiao_ya-medium-int8.tar.bz2               (k2-fsa/sherpa-onnx "tts-models" release)

Writes OUT_DIR/manifest.json and one gzip per file. The app fetches manifest.json first, then only its own ABI's library and
the model files, checking each against the sha256 of the unpacked bytes. Commit OUT_DIR as offline-voice/ in the repository: the app
downloads from its raw.githubusercontent.com address (BuildConfig.OFFLINE_VOICE_BASE). The archives are downloaded and unpacked by this script's
caller; it never touches the network.

The model was trained on the Biaobei (BZNSYP) corpus, which is licensed for non-commercial use only.
"""
import gzip
import hashlib
import json
import pathlib
import shutil
import sys
import tarfile
import tempfile

ABIS = ("arm64-v8a", "armeabi-v7a")  # phones; x86 builds only matter for emulators, and every pack stays in git history
MODEL_FILES = ("zh_CN-xiao_ya-medium.onnx", "tokens.txt", "lexicon.txt", "phone.fst", "date.fst", "number.fst")

if len(sys.argv) != 4:
    sys.exit(__doc__)
lib_archive, model_archive, out = map(pathlib.Path, sys.argv[1:])
out.mkdir(parents=True, exist_ok=True)


def pack(src: pathlib.Path, name: str) -> dict:
    raw = src.read_bytes()
    target = out / f"{name}.gz"
    with gzip.GzipFile(target, "wb", compresslevel=9, mtime=0) as f:  # fixed mtime: the same input packs to the same bytes
        f.write(raw)
    return {"file": target.name, "size": target.stat().st_size, "rawSize": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}


def extract(archive: pathlib.Path, dest: pathlib.Path) -> None:
    """Unpacks regular files and directories only, refusing any path that would land outside [dest]."""
    with tarfile.open(archive) as t:
        for m in t.getmembers():
            if not (m.isfile() or m.isdir()) or not (dest / m.name).resolve().is_relative_to(dest.resolve()):
                sys.exit(f"{archive}: unexpected entry {m.name}")
        t.extractall(dest)


with tempfile.TemporaryDirectory() as tmp:
    libs, model = pathlib.Path(tmp, "libs"), pathlib.Path(tmp, "model")
    extract(lib_archive, libs)
    extract(model_archive, model)
    model_dir = next(p for p in model.iterdir() if p.is_dir())

    manifest = {"version": 1, "libs": {}, "model": {"id": model_dir.name, "files": []}}
    for abi in ABIS:
        manifest["libs"][abi] = pack(libs / "jniLibs" / abi / "libsherpa-onnx-jni.so", f"libsherpa-onnx-jni-{abi}.so")
    for name in MODEL_FILES:
        manifest["model"]["files"].append({"name": name, **pack(model_dir / name, name)})

(out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
total = {abi: l["size"] for abi, l in manifest["libs"].items()}
model_total = sum(f["size"] for f in manifest["model"]["files"])
print(f"wrote {out}/manifest.json")
for abi, s in total.items():
    print(f"  {abi}: library {s / 1e6:.1f} MB + model {model_total / 1e6:.1f} MB = {(s + model_total) / 1e6:.1f} MB download")
