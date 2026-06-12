package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.hapde.HapConfig
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

class GetHapManifestTool : Tool {
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
            val config = Json.decodeFromString<HapConfig>(content)

            val sb = StringBuilder()
            sb.appendLine("=== HAP Manifest ===")
            sb.appendLine("\n[App]")
            sb.appendLine("  bundleName: ${config.app.bundleName}")
            sb.appendLine("  bundleType: ${config.app.bundleType}")
            sb.appendLine("  versionCode: ${config.app.versionCode}")
            sb.appendLine("  versionName: ${config.app.versionName}")
            sb.appendLine("  debug: ${config.app.debug}")
            sb.appendLine("  icon: ${config.app.icon.indexStr}")
            sb.appendLine("  label: ${config.app.label.indexStr}")

            sb.appendLine("\n[Module]")
            sb.appendLine("  name: ${config.module.name}")
            sb.appendLine("  type: ${config.module.type}")
            sb.appendLine("  srcEntry: ${config.module.srcEntry}")
            sb.appendLine("  mainElement: ${config.module.mainElement}")
            sb.appendLine("  deviceTypes: ${config.module.deviceTypes}")
            sb.appendLine("  deliveryWithInstall: ${config.module.deliveryWithInstall}")
            sb.appendLine("  installationFree: ${config.module.installationFree}")

            zip.close()
            sb.toString()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
