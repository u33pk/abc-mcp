package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.hapde.HapConfig
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

class GetHapManifestTool : Tool {
    private val json = Json { ignoreUnknownKeys = true }

    override val name = "get_hap_manifest"
    override val description = "获取 HAP 包的 module.json 清单内容"
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "HAP 文件路径")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("path")) }
    }

    override fun execute(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.content ?: return "Error: path is required"
        val file = File(path)
        if (!file.exists()) return "Error: File not found: $path"

        return try {
            val zip = ZipFile(file)
            val entry = zip.getEntry("module.json")
                ?: return "Error: module.json not found in HAP"

            val content = zip.getInputStream(entry).reader().readText()
            zip.close()

            // 解析并格式化 JSON
            val jsonElement = json.parseToJsonElement(content)
            val prettyJson = json.encodeToString(JsonElement.serializer(), jsonElement)

            // 提取安全关键字段摘要
            val config = json.decodeFromString<HapConfig>(content)
            val sb = StringBuilder()
            sb.appendLine("=== HAP Manifest Summary ===")
            sb.appendLine("  bundleName: ${config.app.bundleName}")
            sb.appendLine("  versionName: ${config.app.versionName}")
            sb.appendLine("  debug: ${config.app.debug}")
            sb.appendLine("  module.name: ${config.module.name}")
            sb.appendLine("  module.type: ${config.module.type}")

            sb.appendLine("\n=== Full module.json ===")
            sb.appendLine(prettyJson)

            sb.toString()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
