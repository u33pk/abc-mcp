package me.yricky.oh.mcp.export

import java.io.File
import java.util.zip.ZipFile

/**
 * 从 HAP 包中提取所有 ABC 文件。
 *
 * 提取目录与 `open_hap` 保持一致：`{hapFile}_abc/`。
 */
object HapAbcExtractor {

    /**
     * 从 [hapFile] 中提取所有 `.abc` 条目。
     *
     * @return 提取出的 ABC 文件列表（按文件名排序）
     */
    fun extract(hapFile: File): List<File> {
        require(hapFile.exists() && hapFile.isFile) { "HAP file not found: ${hapFile.absolutePath}" }

        val extractDir = File(hapFile.parentFile, hapFile.nameWithoutExtension + "_abc").apply { mkdirs() }
        val result = mutableListOf<File>()

        ZipFile(hapFile).use { zip ->
            val abcEntries = zip.entries().toList().filter { it.name.endsWith(".abc", ignoreCase = true) }
            for (entry in abcEntries) {
                val targetFile = File(extractDir, entry.name)
                targetFile.parentFile?.mkdirs()
                if (!targetFile.exists()) {
                    zip.getInputStream(entry).use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                result.add(targetFile)
            }
        }

        return result.sortedBy { it.name }
    }

    /**
     * 从 HAP 路径字符串提取。
     */
    fun extract(hapPath: String): List<File> = extract(File(hapPath))
}
