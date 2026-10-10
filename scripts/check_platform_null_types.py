#!/usr/bin/env python3
"""Compile accepting/rejecting platform-only probes through the real Gradle tasks."""
from __future__ import annotations

import argparse
from pathlib import Path
import subprocess
import sys

BACKENDS = {
    "jvm": ("jvmMain", ":core:compileKotlinJvm"),
    "android": ("androidMain", ":core:compileAndroidMain"),
    "js": ("jsMain", ":core:compileKotlinJs"),
    "wasm": ("wasmJsMain", ":core:compileKotlinWasmJs"),
    "ios": ("iosMain", ":core:compileKotlinIosSimulatorArm64"),
}
ACCEPTING = '''package policyprobe
fun platformPolicyProbe() = mapOf("key" to "value").getValue("key")
'''
REJECTING = '''package policyprobe
fun platformPolicyProbe() = mapOf("key" to "value")["missing"]
'''
REJECTING_EXPRESSION = '''package policyprobe
fun platformPolicyProbe() = mapOf("key" to "value")["missing"].toString()
'''
EXPRESSION_MARKER = "null-safety: nullable or star-projected type of expression"
MARKER = "null-safety: nullable or star-projected type of platformPolicyProbe"


def check_backend(root: Path, backend: str, wrapper: Path) -> bool:
    source_set, task = BACKENDS[backend]
    probe = root / "core" / "src" / source_set / "kotlin" / "policyprobe" / "PlatformPolicyProbe.kt"
    if probe.exists():
        raise RuntimeError(f"Refusing to overwrite an existing probe: {probe}")
    probe.parent.mkdir(parents=True, exist_ok=True)
    command = [str(wrapper), "-p", str(root), task, "-PcheckNullTypes", "--console=plain"]
    try:
        for label, code, marker in (("accepting", ACCEPTING, MARKER),
                                    ("rejecting-declaration", REJECTING, MARKER),
                                    ("rejecting-expression", REJECTING_EXPRESSION, EXPRESSION_MARKER)):
            probe.write_text(code, encoding="utf-8")
            result = subprocess.run(command, cwd=root, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, text=True, timeout=600)
            if label == "accepting":
                if result.returncode != 0:
                    print(result.stdout)
                    print(f"{backend}: accepting platform probe did not compile", file=sys.stderr)
                    return False
                print(f"{backend}: non-null platform declaration accepted", flush=True)
            else:
                if result.returncode == 0:
                    print(f"{backend}: {label} compiled successfully; "
                          "compiler policy is missing", file=sys.stderr)
                    return False
                if marker not in result.stdout:
                    print(result.stdout)
                    print(f"{backend}: rejecting probe failed for a reason other than the policy", file=sys.stderr)
                    return False
                print(f"{backend}: {label} rejected by policy", flush=True)
        return True
    finally:
        probe.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("backends", nargs="+", choices=tuple(BACKENDS))
    root = Path(__file__).resolve().parents[1]
    parser.add_argument("--gradle-wrapper", type=Path, default=root / "gradlew",
                        help="Host coordination wrapper when running on a shared machine")
    arguments = parser.parse_args()
    passed = True
    # Run every requested probe so one missing backend does not hide another.
    for backend in arguments.backends:
        passed = check_backend(root, backend, arguments.gradle_wrapper.resolve()) and passed
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
