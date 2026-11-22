#!/usr/bin/env python
# daemon 客户端: python run.py <jsfile> [--java] [--wait N]
import sys, os, json, time

BASE = r"G:\hook_test"
CMD  = os.path.join(BASE, "daemon_cmd.json")
RES  = os.path.join(BASE, "daemon_result.json")

def send(body, java=False, wait=15):
    try:
        if os.path.exists(RES): os.remove(RES)
    except Exception: pass
    seq = int(time.time() * 1000)
    with open(CMD, "w", encoding="utf-8") as f:
        json.dump({"seq": seq, "body": body, "java": java}, f)
    t0 = time.time()
    while time.time() - t0 < wait:
        if os.path.exists(RES):
            try:
                d = json.load(open(RES, encoding="utf-8"))
                if d.get("seq") == seq: return d
            except Exception: pass
        time.sleep(0.03)
    return {"ok": False, "err": "client timeout"}

if __name__ == "__main__":
    args = [a for a in sys.argv[1:]]
    java = "--java" in args
    args = [a for a in args if not a.startswith("--")]
    wait = 15
    for a in sys.argv[1:]:
        if a.startswith("--wait="):
            wait = float(a.split("=")[1])
    if not args:
        print("usage: run.py <jsfile|-> [--java] [--wait=N]"); sys.exit(1)
    src = args[0]
    body = sys.stdin.read() if src == "-" else open(src, encoding="utf-8").read()
    t0 = time.time()
    r = send(body, java, wait)
    el = time.time() - t0
    # 完整结果写 UTF-8 文件，避免控制台编码破坏中文
    dump = os.path.join(BASE, "last_result.json")
    with open(dump, "w", encoding="utf-8") as f:
        json.dump(r, f, ensure_ascii=False, indent=1)
    agent = r.pop("agent", None) or []
    print("elapsed %.2fs  ok=%s" % (el, r.get("ok")))
    if not r.get("ok"):
        print(json.dumps(r, ensure_ascii=False, indent=1)[:1500])
    print("--- agent output (%d) -> %s ---" % (len(agent), dump))
    for a in agent[:80]:
        line = json.dumps(a, ensure_ascii=False)[:1200]
        # 控制台是 GBK，无法编码的字符用 backslashreplace 兜底，避免整轮结果丢失
        enc = line.encode(sys.stdout.encoding or "utf-8", "backslashreplace")
        print(" ", enc.decode(sys.stdout.encoding or "utf-8", "replace"))
