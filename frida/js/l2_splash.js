// ============================================================
// L2：开屏广告入口拦截 + onNoAD 回调
//
// 为什么在这里做 L2 最合适（实测依据）：
//   开屏广告的监听器是 com.tencentmusic.ad.integration.operationsplash
//   .operationSplash.controller.TMEOperSplashAdListener，方法名不混淆，
//   直接就有 onNoAD(AdError)，还有 onADSkip / onADDismissed。
//
//   开屏广告的特殊性：它不是"显示出来再隐藏"能解决的 —— 素材一旦请求成功，
//   整个 SplashActivity 会被它占住若干秒。必须让它走 onNoAD 分支才会立刻关闭。
//   这正是"主动回调 onNoAd"这个技巧最有价值的地方。
//
// 做法：
//   hook onADFetch(asset) —— 素材到达时不渲染，改为回调 onNoAD，
//   让 App 立刻走"无广告"分支，进首页。
// ============================================================
Java.perform(function () {
  if (globalThis.__l2splash) { _cap({ m: 'already installed' }); return; }

  var ObjectC = Java.use('java.lang.Object');
  var S = globalThis.__l2splashStats = { fetch: 0, noAdCalled: 0, skip: 0, log: [] };
  function log(s) { if (S.log.length < 30) S.log.push(s); }
  function cn(o) { try { var n = String(Java.cast(o, ObjectC).getClass().getName()); return n.substring(n.lastIndexOf('.') + 1); } catch (e) { return '?'; } }

  var LISTENER = 'com.tencentmusic.ad.integration.operationsplash.operationSplash.controller.TMEOperSplashAdListener';
  var L = null;
  try { L = Java.use(LISTENER); } catch (e) { _cap({ err: 'listener missing' }); return; }

  var AdError = null;
  try { AdError = Java.use('com.tencentmusic.ad.integration.error.AdError'); } catch (e) {}

  function makeNoAdError() {
    try { return AdError.$new(204, 'no ad'); }
    catch (e) { try { return AdError.$new(-1, 'no ad'); } catch (e2) { return null; } }
  }

  var hooked = [];

  // onADFetch(asset) —— 素材到达。不渲染，转 onNoAD
  try {
    L.onADFetch.overloads.forEach(function (o) {
      var impl = o.implementation;
      o.implementation = function () {
        S.fetch++;
        log('block onADFetch <- ' + cn(arguments[0]));
        try {
          var err = makeNoAdError();
          if (err) {
            // 主动回调 onNoAD —— 这是关键一步，否则开屏会卡在等超时
            L.onNoAD.call(this, err);
            S.noAdCalled++;
            log('called onNoAD');
          }
        } catch (e) { log('cbErr:' + String(e).slice(0, 60)); }
        // 不调用原实现
      };
    });
    hooked.push('onADFetch');
  } catch (e) { log('fetch:' + e); }

  // onADFetchWithResult(SplashOrder) —— 另一种开屏（GDT 路径）
  try {
    L.onADFetchWithResult.overloads.forEach(function (o) {
      var impl = o.implementation;
      o.implementation = function () {
        S.fetch++;
        log('block onADFetchWithResult');
        try {
          var err = makeNoAdError();
          if (err) { L.onNoAD.call(this, err); S.noAdCalled++; }
        } catch (e) {}
      };
    });
    hooked.push('onADFetchWithResult');
  } catch (e) { log('fetchRes:' + e); }

  // onADSkip —— 跳过时立刻关闭
  try {
    L.onADSkip.overloads.forEach(function (o) {
      var impl = o.implementation;
      o.implementation = function () {
        S.skip++;
        try { L.onADDismissed.call(this); } catch (e) {}
        return impl !== null ? impl.apply(this, arguments) : o.apply(this, arguments);
      };
    });
    hooked.push('onADSkip');
  } catch (e) { log('skip:' + e); }

  globalThis.__l2splash = true;
  _cap({ installed: true, hooked: hooked.join(','), stats: S });
  try { send({ t: 'l2splash-installed', hooked: hooked.join(','), n: hooked.length }); } catch (e) {}
});
'ok'
