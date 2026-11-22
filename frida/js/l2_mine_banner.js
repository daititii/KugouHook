// L2-Lite：让「我的」页 banner 拿不到广告数据（i(mgr) 返回 null），验证 banner 消失
Java.perform(function () {
  if (globalThis.__minebanner) { _cap({ m: 'already' }); return; }

  var CN = 'com.kugou.android.ads.mine.dynamicbannerad.MineAdBannerMainDelegate';
  var C = Java.use(CN);
  var S = globalThis.__minebannerStats = { iCalled: 0, i0Called: 0, log: [] };
  function log(s) { if (S.log.length < 30) S.log.push(s); }

  // 1) hook i(mgr) —— 读广告数据，返回 null 让它以为没数据
  try {
    var ovs = C.i.overloads;
    for (var k = 0; k < ovs.length; k++) {
      (function (o) {
        var at = null; try { at = o.argumentTypes; } catch (e) {}
        if (!at || at.length !== 1) return;
        o.implementation = function () {
          S.iCalled++;
          log('i() -> null');
          return null;
        };
      })(ovs[k]);
    }
  } catch (e) { log('i:' + String(e).slice(0, 60)); }

  // 2) hook i0(Ad) —— 写入广告数据，忽略
  try {
    var ovs2 = C.i0.overloads;
    for (var k2 = 0; k2 < ovs2.length; k2++) {
      (function (o) {
        o.implementation = function () {
          S.i0Called++;
          log('i0() blocked');
        };
      })(ovs2[k2]);
    }
  } catch (e) { log('i0:' + String(e).slice(0, 60)); }

  globalThis.__minebanner = true;
  _cap({ installed: true, stats: S });
  try { send({ t: 'minebanner-installed' }); } catch (e) {}
});
'ok'
