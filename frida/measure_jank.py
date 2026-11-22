#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
measure_jank.py —— 用 dumpsys gfxinfo 量化卡顿

为什么必须"边滚边测"：
  页面静止时 App 不重绘，gfxinfo 的 Total frames 会是 0，
  算不出任何 jank 比例。必须持续产生帧。

用法：
    python measure_jank.py --label no-hook  --seconds 40
    python measure_jank.py --label v11      --seconds 40

原理：
  gfxinfo reset 清零 -> 后台线程持续滑动制造帧 -> 读 Janky frames 百分比
"""
import subprocess, sys, time, json, argparse, threading, os, re

DEV = "1b862d7c"
PKG = "com.kugou.android"


def sh(args, timeout=60):
    return subprocess.run(["adb", "-s", DEV, "shell"] + args,
                          capture_output=True, text=True, timeout=timeout).stdout


def scroll_loop(stop, seconds):
    """设备内循环滑动，避免 adb 往返开销；滚动区域选首页 feed。"""
    end = time.time() + seconds
    # 每批 6 次上滑 + 6 次下滑，批间不 sleep（input 本身有耗时）
    batch = ("for i in 1 2 3 4 5 6; do "
             "input swipe 720 2200 720 800 120; "
             "input swipe 720 800 720 2200 120; "
             "done")
    while time.time() < end and not stop.is_set():
        sh([batch])


def parse_gfx(text):
    out = {}
    def grab(pat, key, cast=float):
        m = re.search(pat, text)
        if m:
            try:
                out[key] = cast(m.group(1))
            except Exception:
                out[key] = m.group(1)
    grab(r"Total frames rendered:\s*(\d+)", "total_frames", int)
    grab(r"Janky frames:\s*(\d+)\s*\(([\d.]+)%\)", "janky_raw", str)
    grab(r"Janky frames:\s*\d+\s*\(([\d.]+)%\)", "janky_pct")
    grab(r"50th percentile:\s*(\d+)", "p50", int)
    grab(r"90th percentile:\s*(\d+)", "p90", int)
    grab(r"95th percentile:\s*(\d+)", "p95", int)
    grab(r"99th percentile:\s*(\d+)", "p99", int)
    grab(r"Number Slow UI thread:\s*(\d+)", "slow_ui", int)
    grab(r"Number Frame deadline missed:\s*(\d+)", "deadline_missed", int)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", required=True)
    ap.add_argument("--seconds", type=int, default=40)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                  "jank_results.json"))
    args = ap.parse_args()

    sh(["dumpsys", "gfxinfo", PKG, "reset"])
    time.sleep(1)

    stop = threading.Event()
    t = threading.Thread(target=scroll_loop, args=(stop, args.seconds), daemon=True)
    t.start()
    time.sleep(args.seconds)
    stop.set()
    t.join(timeout=30)
    time.sleep(2)   # 等帧统计落定

    text = sh(["dumpsys", "gfxinfo", PKG])
    res = parse_gfx(text)
    res["label"] = args.label
    res["seconds"] = args.seconds

    print("\n===== %s =====" % args.label, flush=True)
    print("  Total frames    : %s" % res.get("total_frames"), flush=True)
    print("  Janky frames    : %s%%" % res.get("janky_pct"), flush=True)
    print("  p50/p90/p95/p99 : %s / %s / %s / %s ms" % (
        res.get("p50"), res.get("p90"), res.get("p95"), res.get("p99")), flush=True)
    print("  Slow UI thread  : %s" % res.get("slow_ui"), flush=True)
    print("  Deadline missed : %s" % res.get("deadline_missed"), flush=True)

    allr = []
    if os.path.exists(args.out):
        try:
            allr = json.load(open(args.out, encoding="utf-8"))
        except Exception:
            allr = []
    allr = [x for x in allr if x.get("label") != args.label]
    allr.append(res)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(allr, f, ensure_ascii=False, indent=1)
    print("  -> %s" % args.out, flush=True)


if __name__ == "__main__":
    sys.exit(main())
