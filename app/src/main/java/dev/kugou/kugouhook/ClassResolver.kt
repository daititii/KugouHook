package dev.kugou.kugouhook

import java.lang.reflect.Method

/**
 * 对宿主 ClassLoader 的反射查找。
 *
 * 设计取舍：**不把 DexKit 当作必需依赖。**
 *
 * 追查过的类名（详见 docs/REVERSE-NOTES.md）都是实机 dump 出来的确定值，
 * 先用反射直接命中；DexKit 只在反射失败时作为升级兜底去按特征搜索。
 * 这样即使 DexKit 的 native shim 在某些 ART 版本上加载不了，模块主体依然可用 ——
 * 参考项目里 `libdexkit.so` 必须由模块自己 loadLibrary 才能工作，
 * 否则整个 resolve 阶段会一起失效，这个耦合不该出现在核心路径上。
 *
 * 所有 miss 都记 WARN，绝不静默变成运行期崩溃。
 */
class ClassResolver(
    private val classLoader: ClassLoader,
    private val log: ModuleLog
) {

    private val classCache = HashMap<String, Class<*>?>()
    private val methodCache = HashMap<String, Method?>()

    fun findClass(name: String): Class<*>? {
        classCache[name]?.let { return it }
        val c = try {
            Class.forName(name, false, classLoader)
        } catch (cnfe: ClassNotFoundException) {
            log.warn("class not found: $name")
            null
        } catch (t: Throwable) {
            log.warn("class lookup failed: $name (${t.javaClass.simpleName}: ${t.message})")
            null
        }
        classCache[name] = c
        return c
    }

    /**
     * 按名字 + 参数类型名查方法。
     *
     * 参数类型里允许出现：
     *   - 基本类型：`boolean` `int` `long` `void` …
     *   - 包装类型：`Integer` `Long` …
     *   - `java.lang.*` / `android.*` 全限定名
     *   - 宿主类全限定名
     *
     * 找不到返回 null（调用方据此跳过自己），并记 WARN。
     */
    fun findMethod(className: String, methodName: String, vararg paramTypeNames: String): Method? {
        val key = "$className#$methodName(${paramTypeNames.joinToString(",")})"
        if (methodCache.containsKey(key)) return methodCache[key]

        val c = findClass(className)
        if (c == null) {
            methodCache[key] = null
            return null
        }

        val types = paramTypeNames.map { resolveType(it) }
        val m = try {
            c.getDeclaredMethod(methodName, *types.toTypedArray()).also { it.isAccessible = true }
        } catch (e: NoSuchMethodException) {
            // 退化：按名字扫一遍，签名对不上时也能拿到（并用日志提示签名不一致）
            val byName = c.declaredMethods.firstOrNull { it.name == methodName }
            if (byName == null) {
                log.warn("method not found: $key")
            } else {
                log.warn(
                    "method signature mismatch: $key -> found ${byName.parameterTypes.joinToString(",") { it.simpleName }}"
                )
                byName.isAccessible = true
            }
            byName
        } catch (t: Throwable) {
            log.warn("method lookup failed: $key (${t.javaClass.simpleName})")
            null
        }
        methodCache[key] = m
        return m
    }

    /** 在继承链上找字段（酷狗的 bean 字段常定义在父类）。 */
    fun findField(className: String, fieldName: String): java.lang.reflect.Field? {
        var c = findClass(className) ?: return null
        var depth = 0
        while (depth < 8) {
            try {
                return c.getDeclaredField(fieldName).also { it.isAccessible = true }
            } catch (e: NoSuchFieldException) {
                c = c.superclass ?: break
                depth++
            } catch (t: Throwable) {
                break
            }
        }
        log.warn("field not found: $className.$fieldName")
        return null
    }

    /**
     * 找某个类的所有无参方法里、名字匹配谓词的那些 —— 用于"不知道具体名字但知道形状"的场景。
     */
    fun findNoArgBooleanMethods(className: String): List<Method> {
        val c = findClass(className) ?: return emptyList()
        return c.declaredMethods.filter {
            it.parameterTypes.isEmpty() && it.returnType == java.lang.Boolean.TYPE
        }.onEach { it.isAccessible = true }
    }

    private fun resolveType(name: String): Class<*> {
        return when (name) {
            "boolean" -> java.lang.Boolean.TYPE
            "byte" -> java.lang.Byte.TYPE
            "char" -> java.lang.Character.TYPE
            "short" -> java.lang.Short.TYPE
            "int" -> java.lang.Integer.TYPE
            "long" -> java.lang.Long.TYPE
            "float" -> java.lang.Float.TYPE
            "double" -> java.lang.Double.TYPE
            "void" -> java.lang.Void.TYPE
            "Boolean" -> java.lang.Boolean::class.java
            "Integer" -> java.lang.Integer::class.java
            "Long" -> java.lang.Long::class.java
            "String" -> String::class.java
            "Object" -> Any::class.java
            else -> Class.forName(name, false, classLoader)
        }
    }
}