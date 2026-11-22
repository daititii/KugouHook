#!/usr/bin/env python
"""
常驻 Frida daemon (rpc.exports 版)。
桥只注入一次；之后每次命令通过 rpc.exports.run(body) 执行，可无限次调用。
用法: python frida_daemon.py   （后台运行）
命令: 写 daemon_cmd.json {"seq":N,"body":"...js...","wait":8}
结果: 读 daemon_result.json
"""
import frida, time, subprocess, json, os, sys

DEV   = "1b862d7c"
PKG   = "com.kugou.android"
BASE  = r"G:\hook_test"
BRIDGE_PATH = r"G:\Android_SDK\tools\frida\java-bridge.js"
CMD = os.path.join(BASE, "daemon_cmd.json")
RES = os.path.join(BASE, "daemon_result.json")
LOG = os.path.join(BASE, "daemon.log")

def log(*a):
    s = time.strftime("%H:%M:%S ") + " ".join(str(x) for x in a)
    print(s, flush=True)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(s + "\n")

def find_pid():
    out = subprocess.run(["adb","-s",DEV,"shell","ps","-A","-o","PID,NAME"],
                         capture_output=True, text=True, timeout=20).stdout
    for line in out.splitlines():
        p = line.split()
        if len(p) >= 2 and p[1] == PKG:
            return int(p[0])
    return None

BRIDGE = open(BRIDGE_PATH, encoding="utf-8").read()

# rpc.exports：可重复调用。业务代码用 _cap(x) 收集输出，send() 仍走全局消息
RPC = r"""
var __buf = [];
globalThis._cap = function (x) { try { __buf.push(x); } catch (e) {} };
rpc.exports = {
  run: function (body) {
    __buf = [];
    var res;
    try {
      var r = (0, eval)(body);
      res = { ok: true, ret: String(r === undefined ? 'undefined' : r) };
    } catch (e) {
      res = { ok: false, err: String(e), stack: String(e.stack || '').slice(0, 800) };
    }
    res.agent = __buf.slice(0, 300);
    res.agentCount = __buf.length;
    return res;
  },
  jrun: function (body) {
    __buf = [];
    var res;
    try {
      Java.perform(function () {
        try { res = { ok: true, ret: String((0, eval)(body)) }; }
        catch (e) { res = { ok: false, err: String(e), stack: String(e.stack || '').slice(0, 800) }; }
      });
    } catch (e) {
      res = { ok: false, err: 'perform: ' + String(e) };
    }
    if (!res) res = { ok: false, err: 'perform did not run' };
    res.agent = __buf.slice(0, 300);
    res.agentCount = __buf.length;
    return res;
  },
  ping: function () { return { pong: true, pid: Process.id, java: (typeof Java !== 'undefined') }; }
};
send({ __ready__: true, pid: Process.id });
"""

class D:
    def __init__(self):
        self.s = None; self.sc = None; self.pid = None; self.dead = None

    def ensure(self):
        pid = find_pid()
        if pid is None:
            return False
        if self.s and self.pid == pid and not self.dead:
            return True
        t0 = time.time()
        try:
            dev = frida.get_device(DEV, timeout=10)
            self.pid = pid; self.dead = None
            s = dev.attach(pid)
            s.on("detached", lambda r, c: (setattr(self, 'dead', r), log("[detach]", r)))
            sc = s.create_script(BRIDGE + "\nglobalThis.Java = bridge;\n" + RPC)
            sc.on("message", lambda m, d: log("[agent]", str(m.get("payload"))[:600]))
            sc.load()
        except Exception as e:
            # 应用正在重启/已退出时 attach 会抛异常，不能让它带走整个 daemon
            self.s = None; self.sc = None; self.dead = "attach-failed"
            log("[attach-fail]", type(e).__name__, str(e)[:120])
            return False
        self.s, self.sc = s, sc
        log("[attach] pid=%d in %.1fs" % (pid, time.time() - t0))
        return True

def main():
    log("=== daemon(rpc) starting ===")
    for f in (CMD, RES):
        if os.path.exists(f):
            try: os.remove(f)
            except Exception: pass
    d = D()
    while True:
        if not d.ensure():
            time.sleep(1); continue
        break
    log("=== READY ===")
    last = None
    while True:
        try:
            if not os.path.exists(CMD):
                time.sleep(0.08); continue
            raw = open(CMD, encoding="utf-8").read()
            if raw == last:
                time.sleep(0.08); continue
            last = raw
            try: req = json.loads(raw)
            except Exception: continue
            seq = req.get("seq"); body = req.get("body", ""); use_java = req.get("java", False)
            t0 = time.time()
            try:
                if not d.ensure():
                    r = {"ok": False, "err": "app not running"}
                else:
                    fn = d.sc.exports_sync.jrun if use_java else d.sc.exports_sync.run
                    r = fn(body)
            except Exception as e:
                r = {"ok": False, "err": repr(e)}
                d.dead = "error"
            r["seq"] = seq
            r["elapsed"] = round(time.time() - t0, 2)
            with open(RES, "w", encoding="utf-8") as f:
                json.dump(r, f, ensure_ascii=False)
            log("[done] seq=%s ok=%s %.2fs agent=%d" % (seq, r.get("ok"), r["elapsed"], len(r.get("agent") or [])))
        except Exception as e:
            log("[loop]", repr(e)); time.sleep(0.5)

if __name__ == "__main__":
    main()
