package me.yricky.oh.mcp.export

import kotlinx.serialization.json.*
import me.yricky.oh.common.wrapAsLEByteBuf
import me.yricky.oh.resde.ResIndexBuf
import me.yricky.oh.resde.ResType
import me.yricky.oh.resde.ResourceItem
import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.ZipFile

/**
 * HAP 资源导出器。
 *
 * 将 HAP 包中的资源导出到工程目录：
 * - 值资源（string/color/integer 等）→ `res/values/{qualifier}.json`
 * - 文件资源（media/raw/profile 等）→ `res/{type}/{qualifier}/{fileName}`
 */
object HapResourceExporter {

    private val prettyJson = Json { prettyPrint = true }

    /**
     * 资源导出配置。
     */
    data class Config(
        /** 是否导出值资源 */
        val exportValues: Boolean = true,
        /** 是否导出文件资源 */
        val exportFiles: Boolean = true
    )

    /**
     * 导出结果。
     */
    data class Result(
        val valueFiles: List<String>,
        val fileResources: List<String>,
        val qualifierCount: Int,
        val totalItemCount: Int,
        val errors: List<String>
    )

    private val VALUE_TYPES = setOf(
        ResType.STRING,
        ResType.COLOR,
        ResType.INTEGER,
        ResType.FLOAT,
        ResType.BOOLEAN,
        ResType.PLURAL,
        ResType.STRARRAY,
        ResType.INTARRAY,
        ResType.THEME
    )

    private val FILE_TYPES = setOf(
        ResType.MEDIA,
        ResType.RAW,
        ResType.PROF,
        ResType.PATTERN,
        ResType.SYMBOL,
        ResType.RES
    )

    /**
     * 从 [hapFile] 导出资源到 [outputRoot]/res/。
     */
    fun export(hapFile: File, outputRoot: File, config: Config = Config()): Result {
        require(hapFile.exists() && hapFile.isFile) { "HAP file not found: ${hapFile.absolutePath}" }

        val resRoot = File(outputRoot, "res").apply { mkdirs() }
        val metaRoot = File(outputRoot, "metadata").apply { mkdirs() }

        val errors = mutableListOf<String>()
        val valueFiles = mutableListOf<String>()
        val fileResources = mutableListOf<String>()

        val resBuf = loadResIndex(hapFile) ?: return Result(emptyList(), emptyList(), 0, 0, listOf("Cannot load resources.index"))

        val items = resBuf.resMap.values.flatten()
        val groupedByQualifier = items.groupBy { it.limitKey }

        for ((qualifier, qualifierItems) in groupedByQualifier) {
            val safeQualifier = sanitizeName(qualifier).ifBlank { "base" }

            // 值资源
            if (config.exportValues) {
                val valueItems = qualifierItems.filter { it.resType in VALUE_TYPES }
                if (valueItems.isNotEmpty()) {
                    val valuesDir = File(resRoot, "values").apply { mkdirs() }
                    val targetFile = File(valuesDir, "$safeQualifier.json")
                    val json = buildValueJson(valueItems)
                    runCatching {
                        targetFile.writeText(prettyJson.encodeToString(JsonObject.serializer(), json))
                        valueFiles.add("res/values/$safeQualifier.json")
                    }.onFailure { e ->
                        errors.add("Failed to write values for $qualifier: ${e.message}")
                    }
                }
            }

            // 文件资源
            if (config.exportFiles) {
                val fileItems = qualifierItems.filter { it.resType in FILE_TYPES }
                ZipFile(hapFile).use { zip ->
                    for (item in fileItems) {
                        val typeDir = item.resType.toString().lowercase()
                        val targetDir = File(resRoot, "$typeDir/$safeQualifier").apply { mkdirs() }

                        val copiedName = copyFileFromZip(zip, item, targetDir)
                        if (copiedName != null) {
                            fileResources.add("res/$typeDir/$safeQualifier/$copiedName")
                        } else {
                            // 兜底：把 ResourceItem 中的原始数据写入
                            val targetFile = File(targetDir, item.fileName)
                            runCatching {
                                targetFile.writeBytes(item.data.raw)
                                fileResources.add("res/$typeDir/$safeQualifier/${item.fileName}")
                            }.onFailure { e ->
                                errors.add("Failed to write file resource ${item.fileName}: ${e.message}")
                            }
                        }
                    }
                }
            }
        }

        val qualifiers = groupedByQualifier.keys.toList().sorted()
        val summary = buildJsonObject {
            put("hap", hapFile.absolutePath)
            put("totalItems", items.size)
            put("qualifiers", JsonArray(qualifiers.map { JsonPrimitive(it) }))
            put("valueFiles", JsonArray(valueFiles.map { JsonPrimitive(it) }))
            put("fileResources", JsonArray(fileResources.map { JsonPrimitive(it) }))
            put("errorCount", errors.size)
        }
        File(metaRoot, "resources.json").writeText(prettyJson.encodeToString(JsonObject.serializer(), summary))

        return Result(valueFiles, fileResources, qualifiers.size, items.size, errors)
    }

    /**
     * 将值资源列表构建为 JSON：顶层按资源类型分组，每层按资源名分组。
     */
    private fun buildValueJson(items: List<ResourceItem>): JsonObject {
        val byType = items.groupBy { it.resType.toString().lowercase() }
        return buildJsonObject {
            byType.forEach { (type, typeItems) ->
                putJsonObject(type) {
                    typeItems.forEach { item ->
                        val key = sanitizeName(item.fileName).ifBlank { "_unnamed" }
                        put(key, item.data.asString)
                    }
                }
            }
        }
    }

    /**
     * 尝试从 HAP zip 中复制文件资源。
     *
     * 因为 `ResourceItem.fileName` 通常不含扩展名，而 zip 中的实际文件带有扩展名，
     * 所以先扫描 zip 中匹配 `resources/{qualifier}/{type}/{fileName}.*` 的条目。
     *
     * @return 复制后的文件名（不含目录），失败返回 null
     */
    private fun copyFileFromZip(zip: ZipFile, item: ResourceItem, targetDir: File): String? {
        val typeDir = item.resType.toString().lowercase()
        val prefix1 = "resources/${item.limitKey}/$typeDir/${item.fileName}."
        val prefix2 = "resources/${item.limitKey}/${item.fileName}."
        val exact1 = "resources/${item.limitKey}/$typeDir/${item.fileName}"
        val exact2 = "resources/${item.limitKey}/${item.fileName}"
        val exact3 = item.fileName

        val entries = zip.entries().toList()
        val matched = entries.firstOrNull {
            it.name == exact1 || it.name == exact2 || it.name == exact3
                    || it.name.startsWith(prefix1) || it.name.startsWith(prefix2)
        } ?: return null

        val actualName = matched.name.substringAfterLast('/')
        val targetFile = File(targetDir, actualName)
        return runCatching {
            zip.getInputStream(matched).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            actualName
        }.getOrDefault(null)
    }

    private fun loadResIndex(hapFile: File): ResIndexBuf? {
        return try {
            ZipFile(hapFile).use { zip ->
                val entry = zip.getEntry("resources.index") ?: return null
                val tmpFile = File.createTempFile("res", ".index")
                tmpFile.deleteOnExit()
                zip.getInputStream(entry).transferTo(tmpFile.outputStream())
                ResIndexBuf(wrapAsLEByteBuf(
                    FileChannel.open(tmpFile.toPath())
                        .map(FileChannel.MapMode.READ_ONLY, 0, tmpFile.length())
                        .order(ByteOrder.LITTLE_ENDIAN)
                ))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun sanitizeName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_.-]"), "_").trim('_').ifBlank { "_" }
    }
}
