package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.common.wrapAsLEByteBuf
import me.yricky.oh.resde.IndexString
import me.yricky.oh.resde.ResIndexBuf
import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.ZipFile

class ResolveResourceTool : Tool {
    override val name = "resolve_resource"
    override val description = "解析资源引用（如 \$string:app_name, \$media:icon）"
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "HAP 文件路径或 resources.index 文件路径")
            }
            putJsonObject("reference") {
                put("type", "string")
                put("description", "资源引用（如 \$string:app_name）")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("path"))
            add(JsonPrimitive("reference"))
        }
    }

    override fun execute(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.content ?: return "Error: path is required"
        val reference = args["reference"]?.jsonPrimitive?.content ?: return "Error: reference is required"

        val indexStr = try {
            IndexString(reference)
        } catch (e: Exception) {
            return "Error: Invalid reference format: $reference"
        }

        val resBuf = loadResIndex(path) ?: return "Error: Cannot load resource index from $path"

        val matches = resBuf.resMap.values.flatten().filter { item ->
            item.fileName == indexStr.fileName &&
                (indexStr.type.value < 0 || item.resType == indexStr.type)
        }

        val sb = StringBuilder()
        sb.appendLine("=== Resolve: $reference ===")
        sb.appendLine("Type: ${indexStr.type}")
        sb.appendLine("Name: ${indexStr.fileName}")

        if (matches.isEmpty()) {
            sb.appendLine("\nNo matching resources found")
        } else {
            sb.appendLine("\nFound ${matches.size} variant(s):")
            matches.forEach { item ->
                sb.appendLine("  [${item.limitKey}] ${item.data.asString}")
            }
        }

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
