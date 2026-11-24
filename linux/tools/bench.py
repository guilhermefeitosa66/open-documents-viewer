"""Start the app N times with --bench and print the median timings.

    python3 tools/bench.py [-n 7] [--cmd "python3 -m odv"] [FILE...]

Regressions in these numbers are bugs. Run it before and after a change.
"""

import argparse
import os
import shlex
import statistics
import subprocess
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-n", type=int, default=7)
    ap.add_argument("--cmd", default=f"{sys.executable} -m odv")
    ap.add_argument("files", nargs="*")
    args = ap.parse_args()
    env = dict(os.environ, PYTHONPATH=os.path.join(ROOT, "src"), GTK_A11Y="none")
    runs: dict[str, list[float]] = {}
    for _ in range(args.n):
        out = subprocess.run(shlex.split(args.cmd) + ["--bench", *args.files], env=env,
                             capture_output=True, text=True, timeout=120).stdout
        for line in out.splitlines():
            parts = line.split()
            if not parts or parts[0] != "odv-bench":
                continue
            fields = dict(p.split("=", 1) for p in parts[2:])
            for key, value in fields.items():
                if key.endswith("_ms"):
                    label = f"{parts[1]:8} {fields.get('file', ''):20} {key}"
                    runs.setdefault(label, []).append(float(value))
    for label, values in runs.items():
        print(f"{label:60} median {statistics.median(values):7.1f} ms  (min {min(values):.1f}, n={len(values)})")


if __name__ == "__main__":
    main()
