#!/usr/bin/env python3
"""Measures cold start and "open a document" times on a device or emulator.

Cold start: `am start -W` TotalTime. Opening: the app logs "OpenDocs: document shown in N ms" from the tap to the
first frame of the document. Each file is opened after a force-stop, from a folder already granted to the app and
listed in its recents, so the time includes loading the format's code for the first time.

    tools/measure_open.py --serial emulator-5600 --folder perf big.md Big.kt longer.docx big.xlsx sample.pptx longer.pdf

Use a release build (assembleRelease): debug builds are several times slower.
"""
import argparse
import re
import statistics
import subprocess
import time

PKG = "io.github.guilhermefeitosa66.opendocumentsviewer"


def adb(serial, *args):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True).stdout


def nodes(serial):
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/odv-ui.xml")
    xml = adb(serial, "shell", "cat", "/sdcard/odv-ui.xml")
    out = []
    for m in re.finditer(r"<node [^>]*>", xml):
        n = m.group(0)
        text = (re.search(r' text="([^"]*)"', n) or [None, ""])[1]
        b = list(map(int, re.findall(r"\d+", re.search(r'bounds="([^"]*)"', n).group(1))))
        out.append((text, b))
    return out


def tap(serial, text, tries=6):
    for _ in range(tries):
        for t, b in nodes(serial):
            if t == text:
                adb(serial, "shell", "input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2))
                return True
        adb(serial, "shell", "input", "swipe", "540", "1800", "540", "900", "300")
        time.sleep(0.6)
    return False


def cold_start(serial, runs):
    times = []
    for _ in range(runs):
        adb(serial, "shell", "am", "force-stop", PKG)
        time.sleep(1)
        out = adb(serial, "shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")
        m = re.search(r"TotalTime: (\d+)", out)
        if m:
            times.append(int(m.group(1)))
    return times


def open_time(serial, folder, name):
    adb(serial, "shell", "am", "force-stop", PKG)
    time.sleep(1)
    adb(serial, "shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")
    time.sleep(1.5)
    if not tap(serial, folder):
        return None
    time.sleep(1.5)
    adb(serial, "logcat", "-c")
    if not tap(serial, name):
        return None
    for _ in range(60):
        time.sleep(0.5)
        m = re.findall(r"document shown in (\d+) ms", adb(serial, "logcat", "-d", "-s", "OpenDocs"))
        if m:
            return int(m[-1])
    return None


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--serial", required=True)
    p.add_argument("--folder", required=True, help="name of the granted folder as shown in recents")
    p.add_argument("--runs", type=int, default=3)
    p.add_argument("files", nargs="*")
    a = p.parse_args()
    cs = cold_start(a.serial, 6)
    print(f"cold start: median {statistics.median(cs)} ms  {cs}")
    for f in a.files:
        t = [x for x in (open_time(a.serial, a.folder, f) for _ in range(a.runs)) if x is not None]
        print(f"{f}: median {statistics.median(t) if t else '-'} ms  {t}")


if __name__ == "__main__":
    main()
