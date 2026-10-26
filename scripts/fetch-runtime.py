#!/usr/bin/env python3
"""Fetch pinned upstream static Codex executables; verify each binary before packaging."""
import hashlib
import io
from pathlib import Path
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.163.0"
TARGETS = {
    "arm64-v8a": ("arm64", "aarch64", "8a96f66d059d653e68bfd5f3c79337e6f1d7d170d80bf705eb7d0249b34c0ece"),
    "x86_64": ("x64", "x86_64", "399ff69c431c066e40de318fba45c2b07f45a76b19939d247ee4828fc70c4215"),
}

for abi, (npm_arch, rust_arch, expected) in TARGETS.items():
    url = f"https://registry.npmjs.org/@openai/codex/-/codex-{VERSION}-linux-{npm_arch}.tgz"
    with urllib.request.urlopen(url, timeout=120) as response:
        archive = response.read()
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as package:
        member = package.extractfile(f"package/vendor/{rust_arch}-unknown-linux-musl/bin/codex")
        binary = member.read()
    if hashlib.sha256(binary).hexdigest() != expected:
        raise SystemExit(f"Checksum mismatch for {abi}; runtime not written")
    destination = ROOT / "app/src/main/jniLibs" / abi / "libcodex.so"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(binary)
    destination.chmod(0o755)
    print(f"Verified {abi}: {destination.relative_to(ROOT)}")
