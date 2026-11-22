#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
measure_tabswitch.py v2 —— 用 gfxinfo framestats 精确量化"首页 <-> 我的"切页

为什么不用截图对比：
  底部有播放进度条、封面动画，像素一直在变，"画面稳定"永远等不到，
  实测 12 次里只有 2 次能确认，其余全是超时假数据。

更好的办法：gfxinfo framestats 给出**逐帧**时间戳
  IntendedVsync -> FrameCompleted 就是该帧的端到端耗时。
每次切页前 reset，窗口内的帧就全部属于这次切页，因果关系干净。

用法:
    python measure_tabswitch.py --label no_hook --rounds 8
"""
import subprocess, time, argparse, json, os, re, sys, statistics

DEV = "1b862d7c"
PKG = "com.kugou.android"

# 由 js/find_tabs.js 从 View 树 dump 出的真实坐标（getGlobalVisibleRect 中心）
HOME = (360, 3143)
MINE = (1080, 3143)

FRAME_MS = 1000.0 / 60.0   # 60Hz 下的一帧预算


def sh(args, timeout=90):
    return subprocess.run(["adb", "-s", DEV, "shell"] + args,
                          capture_output=True, text=True, timeout=timeout).stdout


def tap(pt):
    sh(["input", "tap", str(pt[0]), str(pt[1])])


def read_frames():
    """读 framestats，返回每帧端到端耗时(ms)列表。"""
    txt = sh(["dumpsys", "gfxinfo", PKG, "framestats"])
    lines = txt.splitlines()
    idx = None
    header = None
    durs = []
    for ln in lines:
        if ln.startswith("Flags,"):
            header = ln.strip().split(",")
            idx = header.index("FrameCompleted") if "FrameCompleted" in header else None
            if "IntendedVsync" in header:
                ivs = header.index("IntendedVsync")
            else:
                ivs = None
            continue
        if header is None:
            continue
        if not re.match(r"^\d+,", ln.strip()):
            continue
        parts = ln.strip().split(",")
        try:
            fc = int(parts[idx])
            if ivs is not None:
                iv = int(parts[ivs])
                d = (fc - iv) / 1e6
            else:
                d = float(parts[idx])
        except Exception:
            continue
        if d > 0:
            durs.append(d)
    return durs


def stats_of(durs):
    if not durs:
        return {}
    d = sorted(durs)
    n = len(d)
    def pct(p):
        i = min(n - 1, max(0, int(round(p / 100.0 * (n - 1)))))
        return d[i]
    return {
        "frames": n,
        "max_ms": round(d[-1], 1),
        "avg_ms": round(statistics.fmean(d), 1),
        "p50_ms": round(pct(50), 1),
        "p90_ms": round(pct(90), 1),
        "p99_ms": round(pct(99), 1),
        "over_budget": sum(1 for x in d if x > FRAME_MS),
        "over_2x": sum(1 for x in d if x > FRAME_MS * 2),
        "over_4x": sum(1 for x in d if x > FRAME_MS * 4),
        "worst_frames": [round(x, 1) for x in d[-5:]],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", required=True)
    ap.add_argument("--rounds", type=int, default=8)
    ap.add_argument("--settle", type=float, default=1.3, help="每次切页后采样窗口(秒)")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                  "tabswitch_frames.json"))
    args = ap.parse_args()

    # 先回到首页
    tap(HOME)
    time.sleep(2.0)

    windows = []
    for i in range(args.rounds):
        target_name, target = ("mine", MINE) if i % 2 == 0 else ("home", HOME)
        sh(["dumpsys", "gfxinfo", PKG, "reset"])
        time.sleep(0.15)              # 让 reset 生效，避免把上一帧算进来
        tap(target)
        time.sleep(args.settle)
        durs = read_frames()
        st = stats_of(durs)
        st["to"] = target_name
        windows.append(st)
        print("  -> %-4s  frames=%3s  max=%7s ms  p90=%6s ms  >16.7ms=%s" % (
            target_name, st.get("frames"), st.get("max_ms"), st.get("p90_ms"),
            st.get("over_budget")), flush=True)
        time.sleep(0.6)

    # 汇总所有窗口
    allf = []
    for w in windows:
        if w.get("frames"):
            allf.extend([])   # 逐帧明细没保留，只用窗口级统计
    agg = {
        "label": args.label,
        "rounds": args.rounds,
        "settle_s": args.settle,
        "frames_total": sum(w.get("frames", 0) for w in windows),
        "over_budget_total": sum(w.get("over_budget", 0) for w in windows),
        "over_2x_total": sum(w.get("over_2x", 0) for w in windows),
        "over_4x_total": sum(w.get("over_4x", 0) for w in windows),
        "max_ms_worst": max((w.get("max_ms", 0) for w in windows), default=0),
        "avg_p90": round(statistics.fmean([w["p90_ms"] for w in windows if w.get("p90_ms")]), 1)
                   if any(w.get("p90_ms") for w in windows) else 0,
        "windows": windows,
    }
    # 一次切页窗口内超出帧预算的比例
    tot = agg["frames_total"] or 1
    agg["over_budget_pct"] = round(100.0 * agg["over_budget_total"] / tot, 1)

    print("\n===== %s =====" % args.label, flush=True)
    print("  切页次数      : %d" % args.rounds, flush=True)
    print("  采样总帧数    : %d" % agg["frames_total"], flush=True)
    print("  超 16.7ms 帧  : %d (%.1f%%)" % (agg["over_budget_total"], agg["over_budget_pct"]), flush=True)
    print("  超 33ms 帧    : %d" % agg["over_2x_total"], flush=True)
    print("  超 66ms 帧    : %d" % agg["over_4x_total"], flush=True)
    print("  最差单帧      : %.1f ms" % agg["max_ms_worst"], flush=True)
    print("  各窗口 p90 均值: %.1f ms" % agg["avg_p90"], flush=True)

    allr = []
    if os.path.exists(args.out):
        try:
            allr = json.load(open(args.out, encoding="utf-8"))
        except Exception:
            allr = []
    allr = [x for x in allr if x.get("label") != args.label]
    allr.append(agg)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(allr, f, ensure_ascii=False, indent=1)
    print("  -> %s" % args.out, flush=True)


if __name__ == "__main__":
    sys.exit(main())
