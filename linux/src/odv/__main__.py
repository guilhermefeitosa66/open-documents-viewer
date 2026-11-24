import time

_T0 = time.perf_counter()

import sys  # noqa: E402


def run() -> int:
    from odv.main import main

    return main(t0=_T0)


if __name__ == "__main__":
    sys.exit(run())
