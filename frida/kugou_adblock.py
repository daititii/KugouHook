#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
kugou_adblock.py —— 酷狗音乐去广告，一键启动（spawn 早期注入）

为什么要 spawn 而不是 attach：
  广告在 App 启动期就开始请求。attach 方式注入时（通常 2s 后）广告已经
  进内存了，只能事后隐藏。spawn 在进程 resume 之前就装好 hook，
  CopyOnWriteArrayList.add 从第一条数据起就被我们看着。

用法：
    python kugou_adblock.py            # 启动并注入，前台常驻
    python kugou_adblock.py --stats    # 连到已运行进程，打印统计
    python kugou_adblock.py --hours 2  # 指定常驻时长

注意：
  - 不要 hook Activity.onCreate / onResume（libFireEye-rqd.so 会 SIGSEGV）
  - 不要用轮询遍历 View 树（实测 657ms/次，直接卡死）
  - 列表 = CopyOnWriteArrayList，只做 add 拦截 + notify 时清扫
"""
import frida, sys, time, os, json, argparse

DEV = "1b862d7c"
PKG = "com.kugou.android"
BASE = os.path.dirname(os.path.abspath(__file__))
BRIDGE_PATH = r"G:\Android_SDK\tools\frida\java-bridge.js"
# 注入顺序：L1 数据层（主） -> 开屏 L2 -> 我的页 banner
HOOK_PATHS = [
    os.path.join(BASE, "js", "v11_fixed.js"),
    os.path.join(BASE, "js", "l2_splash.js"),
    os.path.join(BASE, "js", "l2_mine_banner.js"),
]
STATS_PATH = os.path.join(BASE, "kugou_adblock_stats.json")

RPC_TAIL = r"""
var __buf = [];
globalThis._cap = function (x) { try { __buf.push(x); } catch (e) {} };
rpc.exports = {
  run: function (body) {
    __buf = [];
    var res;
    try { res = { ok: true, ret: String((0, eval)(body)) }; }
    catch (e) { res = { ok: false, err: String(e), stack: String(e.stack || '').slice(0, 600) }; }
    res.agent = __buf.slice(0, 200);
    return res;
  },
  stats: function () {
    var s = globalThis.__v10stats || null;
    return s ? JSON.parse(JSON.stringify(s)) : null;
  },
  ping: function () { return { pid: Process.id, alive: true }; }
};
"""


def build_script(delay_ms=0):
    bridge = open(BRIDGE_PATH, encoding="utf-8").read()
    parts = []
    for p in HOOK_PATHS:
        if os.path.exists(p):
            parts.append(open(p, encoding="utf-8").read())
        else:
            print("[!] missing hook: %s" % p, flush=True)
    hook = "\n;\n".join(parts)
    # 实测：在 spawn 阶段立刻 Java.use() 会因为没有 App ClassLoader 而卡死启动，
    # 所以延迟注入：先 resume 让 App 正常起来，再装 hook。
    gate = ""
    if delay_ms > 0:
        gate = """
// 延迟 %d ms 再安装 hook
setTimeout(function () {
  try { (0, eval)(%s); } catch (e) { send({ __hook_err__: String(e) }); }
}, %d);
""" % (delay_ms, json.dumps(hook), delay_ms)
        hook = ""
    return bridge + "\nglobalThis.Java = bridge;\n" + RPC_TAIL + "\n" + gate + "\n" + hook


def print_stats(payload):
    if not payload:
        print("[stats] hook 未安装或进程已重启", flush=True)
        return
    print("\n========== 酷狗去广告 · 运行统计 ==========", flush=True)
    print("  拦截广告    : %d" % payload.get("blocked", 0), flush=True)
    print("  清扫残留    : %d" % payload.get("swept", 0), flush=True)
    print("  add 调用    : %d" % payload.get("addCalls", 0), flush=True)
    print("  addAll 调用 : %d" % payload.get("addAllCalls", 0), flush=True)
    print("  notify 次数 : %d" % payload.get("notifies", 0), flush=True)
    print("  已识别列表  : %d" % payload.get("feedLists", 0), flush=True)
    logs = payload.get("log") or []
    if logs:
        print("  明细:", flush=True)
        for l in logs[-15:]:
            print("    -", l, flush=True)
    print("==========================================\n", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--stats", action="store_true", help="只打印统计，不重启应用")
    ap.add_argument("--hours", type=float, default=24.0, help="常驻时长（小时）")
    ap.add_argument("--delay", type=int, default=2500,
                    help="spawn 后延迟多少毫秒装 hook（默认 2500；0 表示立即）")
    args = ap.parse_args()

    dev = frida.get_device(DEV, timeout=10)

    if args.stats:
        # 连到已运行进程读统计（按 pid 查，frida 按进程名 attach 在 spawn 后不可靠）
        import subprocess
        try:
            out = subprocess.run(["adb", "-s", DEV, "shell", "pidof", PKG],
                                 capture_output=True, text=True, timeout=15).stdout.strip()
            pid = int(out.split()[0])
        except Exception as e:
            print("[!] 找不到进程:", repr(e), flush=True)
            return 1
        try:
            session = dev.attach(pid)
        except Exception as e:
            print("[!] 无法 attach pid=%d:" % pid, repr(e), flush=True)
            return 1
        script = session.create_script(
            open(BRIDGE_PATH, encoding="utf-8").read() + "\nglobalThis.Java = bridge;\n" + RPC_TAIL)
        script.load()
        print_stats(script.exports_sync.stats())
        session.detach()
        return 0

    src = build_script(0 if args.delay <= 0 else args.delay)
    print("[*] 脚本大小 %.0f KB（bridge + hook）" % (len(src.encode("utf-8")) / 1024.0), flush=True)
    print("[*] spawn %s ..." % PKG, flush=True)

    try:
        pid = dev.spawn([PKG])
    except Exception as e:
        print("[!] spawn 失败:", repr(e), flush=True)
        return 1
    print("[*] spawned pid=%d" % pid, flush=True)

    session = dev.attach(pid)
    state = {"dead": False}

    def on_detached(reason, crash):
        state["dead"] = True
        print("[!] detached: %s  crash=%s" % (reason, crash), flush=True)

    session.on("detached", on_detached)
    script = session.create_script(src)

    def on_message(m, data):
        t = m.get("type")
        if t == "send":
            print("[agent]", str(m.get("payload"))[:400], flush=True)
        elif t == "error":
            print("[ERROR]", str(m.get("payload"))[:400], flush=True)

    script.on("message", on_message)
    script.load()
    print("[*] hook 已装载，resume ...", flush=True)
    dev.resume(pid)

    deadline = time.time() + args.hours * 3600
    last_report = time.time()
    last_pid = pid
    try:
        while time.time() < deadline:
            time.sleep(5)
            if state["dead"]:
                print("[!] 进程已终止，退出", flush=True)
                break
            # 每 5 分钟落盘一次统计
            if time.time() - last_report > 300:
                last_report = time.time()
                try:
                    st = script.exports_sync.stats()
                    with open(STATS_PATH, "w", encoding="utf-8") as f:
                        json.dump(st or {}, f, ensure_ascii=False, indent=1)
                    print("[stats] blocked=%s swept=%s add=%s" % (
                        (st or {}).get("blocked"), (st or {}).get("swept"),
                        (st or {}).get("addCalls")), flush=True)
                except Exception as e:
                    print("[stats-err]", repr(e), flush=True)
    except KeyboardInterrupt:
        print("\n[*] 用户中断", flush=True)
    finally:
        try:
            st = script.exports_sync.stats()
            print_stats(st)
            with open(STATS_PATH, "w", encoding="utf-8") as f:
                json.dump(st or {}, f, ensure_ascii=False, indent=1)
        except Exception:
            pass
        try:
            session.detach()
        except Exception:
            pass
    return 0


if __name__ == "__main__":
    sys.exit(main())



