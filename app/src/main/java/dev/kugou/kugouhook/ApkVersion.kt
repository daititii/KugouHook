package dev.kugou.kugouhook

import java.util.zip.ZipFile

/**
 * 直接从宿主 APK 的二进制 `AndroidManifest.xml` 里读 `versionCode`。
 *
 * 为什么不用更"简单"的反射路线：在 `onPackageReady` 这个时机，Application 对象还不存在。
 *
 *   - `PackageManager.getPackageArchiveInfo` → 需要 Context，拿不到
 *   - `ActivityThread.currentApplication()`  → `IllegalStateException: currentApplication returned null`
 *   - `ApplicationInfo.versionCode`          → **没有这个字段**（`versionCode`/`longVersionCode`
 *                                              属于 `PackageInfo`，不属于 `ApplicationInfo`）
 *
 * 前两条会把版本号塌成 -1，第三条直接编译不过。自己解析 manifest 只用公开稳定的容器格式
 * （ZIP + AXML chunk 布局），不碰 hidden API，也不需要 Context。
 *
 * 这里实现的布局（frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h）：
 *
 *   ResChunk_header      { u16 type; u16 headerSize; u32 size }
 *   RES_XML_TYPE              0x0003  – 文件头
 *   RES_STRING_POOL_TYPE      0x0001  – 字符串池
 *   RES_XML_START_ELEMENT_TYPE 0x0102 – 起始元素节点
 *
 *   字符串池体（8 字节 chunk header 之后）：
 *     u32 stringCount; u32 styleCount; u32 flags; u32 stringsStart; u32 stylesStart;
 *     u32 stringOffsets[stringCount]; ... UTF-8（flag 0x100）或 UTF-16 字符串数据
 *
 *   起始元素节点：
 *     ResChunk_header(8) + u32 lineNumber + u32 comment          → attrExt 位于 +16
 *     attrExt: u32 ns; u32 name; u16 attributeStart; u16 attributeSize;
 *              u16 attributeCount; u16 idIndex; u16 classIndex; u16 styleIndex
 *     attribute: u32 ns; u32 name; u32 rawValue;
 *                u16 size; u8 res0; u8 dataType; u32 data
 *
 * 移植自 FanqieHook（dev.operit.fanqiehook.ApkVersion），保持同一套实测结论。
 */
internal object ApkVersion {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val TYPE_INT_DEC = 0x10
    private const val FLAG_UTF8 = 0x100

    /** 返回 APK 的 `versionCode`；manifest 读不到/解析失败时返回 null。 */
    fun readVersionCode(apkPath: String): Long? = runCatching {
        ZipFile(apkPath).use { zf ->
            val entry = zf.getEntry("AndroidManifest.xml") ?: return null
            val bytes = zf.getInputStream(entry).use { it.readBytes() }
            parseVersionCode(bytes)
        }
    }.getOrNull()

    private fun parseVersionCode(data: ByteArray): Long? {
        if (data.size < 8) return null
        if (u16(data, 0) != RES_XML_TYPE) return null

        var strings: StringPool? = null
        var off = 8 // 跳过文件头 chunk
        while (off + 8 <= data.size) {
            val type = u16(data, off)
            val headerSize = u16(data, off + 2)
            val size = u32(data, off + 4)
            if (size <= 0 || off + size > data.size) return null

            when (type) {
                RES_STRING_POOL_TYPE -> strings = StringPool(data, off, headerSize)
                RES_XML_START_ELEMENT_TYPE -> {
                    val pool = strings ?: return null
                    val attrsOff = off + 16 // ResXMLTree_node 头
                    val nameIdx = u32(data, attrsOff + 4)
                    if (nameIdx in 0 until pool.size && pool.get(nameIdx) == "manifest") {
                        val attributeStart = u16(data, attrsOff + 8)
                        val attributeSize = u16(data, attrsOff + 10)
                        val attributeCount = u16(data, attrsOff + 12)
                        var a = attrsOff + attributeStart
                        repeat(attributeCount) {
                            if (a + attributeSize <= data.size) {
                                val attrNameIdx = u32(data, a + 4)
                                val dataType = data[a + 15].toInt() and 0xFF
                                if (attrNameIdx in 0 until pool.size &&
                                    pool.get(attrNameIdx) == "versionCode" &&
                                    dataType == TYPE_INT_DEC
                                ) {
                                    return u32(data, a + 16).toLong() and 0xFFFFFFFFL
                                }
                            }
                            a += attributeSize
                        }
                    }
                }
            }
            off += size
        }
        return null
    }

    private class StringPool(private val data: ByteArray, private val base: Int, headerSize: Int) {
        private val offsets: IntArray
        private val count: Int
        private val utf8: Boolean
        private val stringsStart: Int

        init {
            count = u32(data, base + 8)
            val flags = u32(data, base + 16)
            utf8 = (flags and FLAG_UTF8) != 0
            stringsStart = u32(data, base + 20)
            val tableStart = base + headerSize
            offsets = IntArray(count) { i -> u32(data, tableStart + 4 * i) }
        }

        val size: Int get() = count

        fun get(index: Int): String? = runCatching {
            var p = base + stringsStart + offsets[index]
            if (utf8) {
                // u8/u16 utf16 长度，然后 u8/u16 字节长度，然后 MUTF-8 字节
                p += skipVarLen8(p)
                val byteLen = readVarLen8(p)
                val start = byteLen.second
                String(data, start, byteLen.first, Charsets.UTF_8)
            } else {
                var len = u16(data, p)
                p += 2
                if (len and 0x8000 != 0) { // 32 位长度
                    len = ((len and 0x7FFF) shl 16) or u16(data, p)
                    p += 2
                }
                String(data, p, len * 2, Charsets.UTF_16LE)
            }
        }.getOrNull()

        /** 跳过 1 或 2 字节长度字段，返回新偏移。 */
        private fun skipVarLen8(p: Int): Int = if (data[p].toInt() and 0x80 != 0) 2 else 1

        /** 读 1 或 2 字节长度字段，返回 (长度, 字段之后偏移)。 */
        private fun readVarLen8(p: Int): Pair<Int, Int> {
            val first = data[p].toInt() and 0xFF
            return if (first and 0x80 != 0) {
                (((first and 0x7F) shl 8) or (data[p + 1].toInt() and 0xFF)) to (p + 2)
            } else {
                first to (p + 1)
            }
        }
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
