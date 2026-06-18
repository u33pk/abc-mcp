package me.yricky.oh.abcd.decompiler.export

import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.xref.ClassNameResolver
import me.yricky.oh.common.value

/**
 * 将 ABC 类名解析为导出的源文件相对路径。
 *
 * 优先从方法 debug info 的 `SetFile` 记录中提取真实源文件路径；
 * 若 debug info 缺失，则回退为从类名推断。
 */
object SourcePathResolver {

    /**
     * 解析 [clazz] 对应的导出文件相对路径（不含输出根目录前缀）。
     *
     * @param clazz ABC 类
     * @param defaultExt 默认扩展名（debug info 不含扩展名或缺失时使用），如 "ets"
     * @return 相对路径，例如 "entry/src/main/ets/pages/Index.ets"
     */
    fun resolve(clazz: AbcClass, defaultExt: String = "ets"): String {
        // 1. 尝试从 debug info 的 SetFile 提取真实源文件路径
        val debugPath = resolveFromDebugInfo(clazz)
        if (debugPath != null) {
            return sanitizePath(debugPath)
        }

        // 2. fallback：从类名推断
        return resolveFromClassName(clazz.name, defaultExt)
    }

    /**
     * 从类中所有方法的 debug info 收集 SetFile 路径，取出现次数最多的一条。
     */
    private fun resolveFromDebugInfo(clazz: AbcClass): String? {
        val pathCounts = mutableMapOf<String, Int>()

        for (method in clazz.methods) {
            if (method !is me.yricky.oh.abcd.cfm.AbcMethod) continue
            val dbgInfo = method.debugInfo?.info ?: continue
            val lnp = dbgInfo.lineNumberProgram ?: continue
            val state = runCatching { lnp.eval(dbgInfo) }.getOrNull() ?: continue

            for (item in state.addressLineColumns) {
                if (item is me.yricky.oh.abcd.code.SetFile) {
                    val path = try {
                        method.abc.stringItem(item.nameIdx).value
                    } catch (_: Throwable) {
                        continue
                    }
                    val realPath = stripModuleVersionPrefix(path)
                    if (realPath.isNotBlank()) {
                        pathCounts[realPath] = pathCounts.getOrDefault(realPath, 0) + 1
                    }
                }
            }
        }

        if (pathCounts.isEmpty()) return null
        return pathCounts.maxByOrNull { it.value }?.key
    }

    /**
     * 剥离 `module|module|version|` 前缀。
     *
     * 例如 `entry|entry|1.0.0|src/main/ets/pages/Index.ts`
     * → `src/main/ets/pages/Index.ts`
     */
    internal fun stripModuleVersionPrefix(path: String): String {
        val lastPipe = path.lastIndexOf('|')
        return if (lastPipe >= 0) path.substring(lastPipe + 1) else path
    }

    /**
     * 从类名推断源文件路径。
     */
    private fun resolveFromClassName(className: String, defaultExt: String): String {
        var normalized = ClassNameResolver.normalizeClassName(className)
            .trim()
            .trimStart('/')
            .trimEnd('/')

        // 去掉系统模块前缀（如 @ohos:app → app）
        if (normalized.startsWith("@")) {
            normalized = normalized.substringAfter(":")
        }

        if (normalized.isEmpty()) {
            normalized = "_unknown"
        }

        // 如果有 / 就当作路径；否则放到根目录
        val hasPath = '/' in normalized
        val baseName = normalized.substringAfterLast('/')
        val dirPart = if (hasPath) normalized.substringBeforeLast('/') else ""

        val safeName = sanitizeFileName(baseName)
        val safeDir = dirPart.split('/').map { sanitizeFileName(it) }.joinToString("/")

        val ext = extensionFromPath(normalized) ?: defaultExt
        val nameWithoutExt = safeName.substringBeforeLast('.', safeName)

        val result = if (safeDir.isBlank()) {
            "$nameWithoutExt.$ext"
        } else {
            "$safeDir/$nameWithoutExt.$ext"
        }
        return sanitizePath(result)
    }

    /**
     * 从路径中识别已有扩展名。
     */
    private fun extensionFromPath(path: String): String? {
        val base = path.substringAfterLast('/')
        val ext = base.substringAfterLast('.', "")
        return when (ext.lowercase()) {
            "ets", "ts", "js" -> ext
            else -> null
        }
    }

    /**
     * 清洗文件路径，防止目录穿越和非法字符。
     */
    fun sanitizePath(path: String): String {
        return path.split('/')
            .filter { it.isNotBlank() && it != ".." }
            .map { sanitizeFileName(it) }
            .joinToString("/")
    }

    /**
     * 清洗单个文件名/目录名。
     */
    fun sanitizeFileName(name: String): String {
        return name.map { c ->
            when (c) {
                ':', '*', '?', '"', '<', '>', '|', '\u0000' -> '_'
                else -> c
            }
        }.joinToString("")
    }
}
