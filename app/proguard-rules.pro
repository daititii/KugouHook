# LSPosed 模块的 R8 保留规则
#
# 注意：Debug 构建不走 R8，所以这些规则写错了在本地开发时**完全看不出来** ——
# 表现为 Release 包装上之后模块静默不生效（framework 按类名找不到入口）。
# 改动本文件后必须跑一次 `./gradlew :app:assembleRelease` 并确认入口类还在。

# ── 1. 模块入口 ───────────────────────────────────────────────────────────────
# META-INF/xposed/java_init.list 里是按【类名字符串】引用入口的，被 R8 改名或删除会在
# 加载期直接失败（framework 找不到类）。入口类必须整类保留。
-keep class dev.kugou.kugouhook.KugouModule { *; }

# 模块自己的其余代码同样整包保留：体积很小，删了省不出什么，但混淆后如果
# 有任何按名字查找（日志、调试、异常栈）都会变得难排查。
-keep class dev.kugou.kugouhook.** { *; }

# ── 2. libxposed API ──────────────────────────────────────────────────────────
# 该 API 由框架在运行时提供（compileOnly，不打包进 APK）。R8 看不到实现，需要
# 抑制警告；我们重写的生命周期方法由框架按签名回调，不能被改名。
-dontwarn io.github.libxposed.**
-keep class io.github.libxposed.** { *; }
-keepclassmembers class * extends io.github.libxposed.api.XposedModule {
    public <init>(...);
    public void onModuleLoaded(...);
    public void onPackageLoaded(...);
    public void onPackageReady(...);
    public boolean onHotReloading(...);
    public void onHotReloaded(...);
}

# ── 3. 反射目标（宿主类） ─────────────────────────────────────────────────────
# 本模块对宿主只做反射调用（ClassResolver），宿主类名/方法名都是运行时才拼出来的
# 字符串，R8 不会也不该去动它们；这里只是把这类"必然找不到定义"的警告压掉，
# 否则 release 构建会因为一堆 missing class 警告而失败。
-dontwarn android.**
-keep class android.content.pm.ApplicationInfo { *; }

# ── 4. Kotlin ────────────────────────────────────────────────────────────────
# Kotlin 标准库是本模块最大的体积来源，可以大幅裁剪；但元数据与内联函数的断言
# 需要保留属性。
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# ── 5. 通用 ───────────────────────────────────────────────────────────────────
# 保留行号，便于实机异常栈定位（对体积影响极小）。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
