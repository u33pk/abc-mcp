package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.common.wrapAsLEByteBuf
import me.yricky.oh.resde.ResIndexBuf
import me.yricky.oh.resde.ResType
import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.ZipFile

class SearchResourcesTool : Tool {
    override val name = "search_resources"
    override val description = "在 HAP 包的 resources.index 中搜索资源（按名称、类型）"
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "HAP 文件路径或 resources.index 文件路径")
            }
            putJsonObject("pattern") {
                put("type", "string")
                put("description", "资源名称匹配模式（正则表达式）")
            }
            putJsonObject("type") {
                put("type", "string")
                put("description", "资源类型过滤（string, media, color, integer 等）")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("path"))
            add(JsonPrimitive("pattern"))
        }
    }

    override fun execute(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.content ?: return "Error: path is required"
        val pattern = args["pattern"]?.jsonPrimitive?.content ?: return "Error: pattern is required"
        val typeFilter = args["type"]?.jsonPrimitive?.content

        val resBuf = loadResIndex(path) ?: return "Error: Cannot load resource index from $path"

        val regex = Regex(pattern)
        val typeMap = mapOf(
            "string" to ResType.STRING, "media" to ResType.MEDIA,
            "color" to ResType.COLOR, "integer" to ResType.INTEGER,
            "boolean" to ResType.BOOLEAN, "float" to ResType.FLOAT
        )
        val targetType = typeFilter?.let { typeMap[it.lowercase()] }

        val results = resBuf.resMap.values.flatten().filter { item ->
            regex.containsMatchIn(item.fileName) &&
                (targetType == null || item.resType == targetType)
        }

        val sb = StringBuilder()
        sb.appendLine("Resources matching '$pattern'" +
            (typeFilter?.let { " (type=$it)" } ?: "") +
            ": ${results.size} results")

        results.take(50).forEach { item ->
            sb.appendLine("  [${item.resType}] ${item.fileName} = ${item.data.asString}")
            sb.appendLine("    qualifier: ${item.limitKey}")
        }
        if (results.size > 50) sb.appendLine("  ... and ${results.size - 50} more")

        return sb.toString()
    }

    private fun loadResIndex(path: String): ResIndexBuf? {
        val file = File(path)
        if (!file.exists()) return null

        return if (path.endsWith(".hap")) {
            val zip = ZipFile(file)
            val entry = zip.getEntry("resources.index") ?: return null
            val tmpFile = File.createTempFile("res", ".index")
            tmpFile.deleteOnExit()
            zip.getInputStream(entry).transferTo(tmpFile.outputStream())
            zip.close()
            ResIndexBuf(wrapAsLEByteBuf(
                FileChannel.open(tmpFile.toPath())
                    .map(FileChannel.MapMode.READ_ONLY, 0, tmpFile.length())
                    .order(ByteOrder.LITTLE_ENDIAN)
            ))
        } else {
            ResIndexBuf(wrapAsLEByteBuf(
                FileChannel.open(file.toPath())
                    .map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                    .order(ByteOrder.LITTLE_ENDIAN)
            ))
        }
    }
}
