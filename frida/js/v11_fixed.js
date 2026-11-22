// ============================================================
// 酷狗去广告 v11 —— 修正重载参数转发 bug
//
// 关键修复（这是 v8/v9/v10 全部崩溃的真正根因）：
//   CopyOnWriteArrayList 有两个 add 重载：
//       add(E)            1 参数
//       add(int index, E) 2 参数
//   早先实现统一写成 function(a){ ... o.call(this, a) }，
//   对 2 参数重载会丢掉 element，往列表插入 null。
//   而 EventBus 2.x 的订阅表正是 CopyOnWriteArrayList<Subscription>，
//   于是立即出现：
//       Subscription.subscriberMethod ... on a null object reference
//       （subscribe / postToSubscription / unsubscribeByEventType 三处都炸）
//   => 必须按重载参数个数分别处理，且原样转发所有实参。
//
// 设计（安全、事件驱动、零轮询）：
//   1) COW.add(E) 单参数重载：广告 bean 直接丢弃（ViewHolder 从不创建）
//   2) COW.addAll(Collection) 单参数重载：过滤后逐条添加
//   3) notify* 时清扫残留（app 自己就要全量重排，安全）
//   不做：不 hook getItemCount、不遍历 View 树、不轮询
// ============================================================
Java.perform(function () {
  if (globalThis.__v11) { _cap({ m: 'already installed' }); return; }

  var ADC = Java.use('com.kugou.android.app.newtingtab.mc');
  var COW = Java.use('java.util.concurrent.CopyOnWriteArrayList');
  var ObjectC = Java.use('java.lang.Object');
  var SystemC = Java.use('java.lang.System');
  var Collection = Java.use('java.util.Collection');

  var S = globalThis.__v11stats = {
    blocked: 0, swept: 0, addCalls: 0, addAllCalls: 0, notifies: 0,
    feedLists: 0, add2Skipped: 0, log: []
  };
  function log(s) { if (S.log.length < 40) S.log.push(s); }

  // ---------- 原方法调用：完整转发实参，避免丢参 ----------
  function callOrig(ov, impl, self, args) {
    try {
      if (typeof ov.apply === 'function') return ov.apply(self, args);
    } catch (e) {}
    try {
      if (impl !== null && typeof impl.apply === 'function') return impl.apply(self, args);
    } catch (e) {}
    // 最后兜底：把 arguments 展开成 call
    var a = [self];
    for (var i = 0; i < args.length; i++) a.push(args[i]);
    return ov.call.apply(ov, a);
  }

  // ---------- 广告判定 ----------
  var modCache = {};
  function modFieldOf(cls) {
    var key = String(cls);
    if (key in modCache) return modCache[key];
    var c = cls, f = null;
    for (var d = 0; c && d < 8; d++) {
      try { f = c.getDeclaredField('module'); f.setAccessible(true); break; } catch (e) {}
      try { c = c.getSuperclass(); } catch (e) { break; }
    }
    modCache[key] = f;
    return f;
  }
  function classOf(o) { try { return String(Java.cast(o, ObjectC).getClass().getName()); } catch (e) { return '?'; } }
  function isAd(o) {
    try {
      var c = Java.cast(o, ObjectC).getClass();
      if (String(c.getName()).indexOf('AdBean') >= 0) return true;
      var f = modFieldOf(c);
      if (f) { try { if (String(f.get(o)).indexOf('_ad') >= 0) return true; } catch (e) {} }
    } catch (e) {}
    return false;
  }
  function isFeedBean(o) {
    return classOf(o).indexOf('com.kugou.android.app.newtingtab.bean.') === 0;
  }

  // ---------- 列表登记（identityHashCode，不能用 hashCode：它是按内容算的）----------
  var feedIds = {};
  function reg(o) {
    try {
      var id = String(SystemC.identityHashCode(o));
      if (!feedIds[id]) { feedIds[id] = true; S.feedLists++; }
    } catch (e) {}
  }
  function isFeed(o) {
    try { return feedIds[String(SystemC.identityHashCode(o))] === true; } catch (e) { return false; }
  }

  // ---------- 1) 只 hook 单参数 add(E) ----------
  var addOvs = COW.add.overloads;
  for (var i = 0; i < addOvs.length; i++) {
    var ov = addOvs[i];
    var argN = 0;
    try { argN = ov.argumentTypes.length; } catch (e) { argN = -1; }
    if (argN !== 1) { S.add2Skipped++; continue; }   // add(int,E) 一律不碰
    (function (ov) {
      var impl = ov.implementation;
      ov.implementation = function () {
        var args = arguments;
        S.addCalls++;
        try {
          var a = args[0];
          if (a) {
            var ad = isAd(a);
            if (!ad && isFeedBean(a)) reg(this);
            if (ad && isFeed(this)) {
              S.blocked++;
              log('block ' + classOf(a).substring(classOf(a).lastIndexOf('.') + 1));
              return false;
            }
          }
        } catch (e) {}
        return callOrig(ov, impl, this, args);
      };
    })(ov);
  }

  // ---------- 2) 只 hook 单参数 addAll(Collection) ----------
  var allOvs = COW.addAll.overloads;
  for (var j = 0; j < allOvs.length; j++) {
    var ov2 = allOvs[j];
    var n2 = 0;
    try { n2 = ov2.argumentTypes.length; } catch (e) { n2 = -1; }
    if (n2 !== 1) continue;
    (function (ov2) {
      var impl2 = ov2.implementation;
      ov2.implementation = function () {
        var args = arguments;
        S.addAllCalls++;
        try {
          var c = args[0];
          if (c) {
            var L = Java.cast(c, Collection);
            var arr = L.toArray();
            var hasAd = false;
            for (var k = 0; k < arr.length; k++) if (isAd(arr[k])) { hasAd = true; break; }
            if (hasAd) {
              for (var m = 0; m < arr.length; m++) {
                if (!isAd(arr[m]) && isFeedBean(arr[m])) { reg(this); break; }
              }
              for (var q = 0; q < arr.length; q++) {
                if (isAd(arr[q]) && isFeed(this)) { S.blocked++; continue; }
                try { this.add(arr[q]); } catch (e) { log('reAdd:' + e); }
              }
              return true;
            }
          }
        } catch (e) { log('addAll:' + e); }
        return callOrig(ov2, impl2, this, args);
      };
    })(ov2);
  }

  // ---------- 3) notify* 时清扫 ----------
  function sweepAdapter(adapter) {
    try {
      var f = null;
      var K = ADC.class;
      for (var d = 0; K && d < 8; d++) {
        try { f = K.getDeclaredField('r'); f.setAccessible(true); break; } catch (e) {}
        try { K = K.getSuperclass(); } catch (e) { break; }
      }
      if (!f) return 0;
      var l = f.get(adapter);
      if (!l) return 0;
      reg(l);
      var L = Java.cast(l, COW);
      var n = 0;
      for (var i2 = L.size() - 1; i2 >= 0; i2--) {
        var it = null; try { it = L.get(i2); } catch (e) { continue; }
        if (it && isAd(it)) { try { L.remove(i2); n++; S.swept++; } catch (e) {} }
      }
      if (n > 0) log('swept ' + n);
      return n;
    } catch (e) { return 0; }
  }

  var NOTIFIES = ['notifyDataSetChanged', 'notifyItemRangeInserted', 'notifyItemRangeRemoved',
                  'notifyItemRemoved', 'notifyItemInserted', 'notifyItemRangeChanged'];
  NOTIFIES.forEach(function (nm) {
    try {
      var m = ADC[nm];
      if (!m) return;
      var ovs = m.overloads;
      for (var k = 0; k < ovs.length; k++) {
        (function (ov) {
          var implN = ov.implementation;
          ov.implementation = function () {
            S.notifies++;
            try { sweepAdapter(this); } catch (e) {}
            return callOrig(ov, implN, this, arguments);
          };
        })(ovs[k]);
      }
    } catch (e) { log('hook ' + nm + ':' + e); }
  });

  globalThis.__v11 = true;
  globalThis.__v11_sweep = function (adapter) { return sweepAdapter(adapter); };

  _cap({ installed: true, stats: S });
  try { send({ t: 'v11-installed', stats: S }); } catch (e) {}
});
'ok'
