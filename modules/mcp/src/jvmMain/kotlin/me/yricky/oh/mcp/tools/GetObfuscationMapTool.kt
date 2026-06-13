package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.hapde.NameCache
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

class GetObfuscationMapTool : Tool {
    private val json = Json { ignoreUnknownKeys = true }

    override val name = "get_obfuscation_map"
    override val description = "获取 HAP 包中的混淆映射（obfuscated → original 方法名）"
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "HAP 文件路径")
            }
            putJsonObject("pattern") {
                put("type", "string")
                put("description", "可选的过滤模式（正则表达式，匹配混淆后名称）")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("path")) }
    }

    override fun execute(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.content ?: return "Error: path is required"
        val pattern = args["pattern"]?.jsonPrimitive?.content
        val file = File(path)
        if (!file.exists()) return "Error: File not found: $path"

        return try {
            val zip = ZipFile(file)
            val entry = zip.getEntry("obfuscation.map")
                ?: zip.getEntry("ets/obfuscation.map")
                ?: return "Error: obfuscation.map not found in HAP"

            val content = zip.getInputStream(entry).reader().readText()
            val nameCache = json.decodeFromString<NameCache>(content)

            val regex = pattern?.let { Regex(it) }
            val sb = StringBuilder()
            sb.appendLine("=== Obfuscation Map ===")
            sb.appendLine("Name: ${nameCache.obfName}")

            val methods = if (regex != null) {
                nameCache.memberMethodCache.filter { regex.containsMatchIn(it.key) }
            } else {
                nameCache.memberMethodCache
            }

            val identifiers = if (regex != null) {
                nameCache.identifierCache.filter { regex.containsMatchIn(it.key) }
            } else {
                nameCache.identifierCache
            }

            sb.appendLine("\n--- Methods (${methods.size}) ---")
            methods.entries.take(100).forEach { (obf, original) ->
                sb.appendLine("  $obf -> $original")
            }
            if (methods.size > 100) sb.appendLine("  ... and ${methods.size - 100} more")

            sb.appendLine("\n--- Identifiers (${identifiers.size}) ---")
            identifiers.entries.take(100).forEach { (obf, original) ->
                sb.appendLine("  $obf -> $original")
            }
            if (identifiers.size > 100) sb.appendLine("  ... and ${identifiers.size - 100} more")

            zip.close()
            sb.toString()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
