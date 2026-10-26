#!/usr/bin/env python3
"""Fetch pinned upstream static Codex executables; verify each binary before packaging."""
import hashlib
import io
from pathlib import Path
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.114.0"
TARGETS = {
    "arm64-v8a": ("arm64", "aarch64", "47da2ec8209ced6102d9817551eae0751320e9ee8453df5a2d33dec685af1935"),
    "x86_64": ("x64", "x86_64", "847939c01c628eaad8a8754ac9eb365c03c55a24d5264a6f2deba8fc67c909b4"),
}

for abi, (npm_arch, rust_arch, expected) in TARGETS.items():
    url = f"https://registry.npmjs.org/@openai/codex/-/codex-{VERSION}-linux-{npm_arch}.tgz"
    with urllib.request.urlopen(url, timeout=120) as response:
        archive = response.read()
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as package:
        member = package.extractfile(f"package/vendor/{rust_arch}-unknown-linux-musl/codex/codex")
        binary = member.read()
    if hashlib.sha256(binary).hexdigest() != expected:
        raise SystemExit(f"Checksum mismatch for {abi}; runtime not written")
    destination = ROOT / "app/src/main/jniLibs" / abi / "libcodex.so"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(binary)
    destination.chmod(0o755)
    print(f"Verified {abi}: {destination.relative_to(ROOT)}")
