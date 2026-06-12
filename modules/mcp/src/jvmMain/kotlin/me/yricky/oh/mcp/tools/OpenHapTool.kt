package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.common.wrapAsLEByteBuf
import me.yricky.oh.hapde.HapConfig
import me.yricky.oh.hapde.HapSignBlocks
import me.yricky.oh.hapde.NameCache
import me.yricky.oh.resde.ResIndexBuf
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.ZipFile

class OpenHapTool : Tool {
    override val name = "open_hap"
    override val description = "解析 HAP 包，列出基本信息、ABC 文件和资源"
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

        val sb = StringBuilder()
        sb.appendLine("=== HAP File: $path ===")
        sb.appendLine("Size: ${file.length()} bytes")

        try {
            val zip = ZipFile(file)
            val entries = zip.entries().toList()

            // Parse manifest
            val moduleJson = entries.find { it.name == "module.json" }
            if (moduleJson != null) {
                val config = Json.decodeFromString<HapConfig>(
                    zip.getInputStream(moduleJson).reader().readText()
                )
                sb.appendLine("\n--- Manifest ---")
                sb.appendLine("Bundle: ${config.app.bundleName}")
                sb.appendLine("Version: ${config.app.versionName} (${config.app.versionCode})")
                sb.appendLine("Module: ${config.module.name} (${config.module.type})")
                sb.appendLine("Devices: ${config.module.deviceTypes}")
            }

            // List ABC files
            val abcFiles = entries.filter { it.name.endsWith(".abc") }
            sb.appendLine("\n--- ABC Files (${abcFiles.size}) ---")
            abcFiles.forEach { sb.appendLine("  ${it.name} (${it.size} bytes)") }

            // List resources
            val resIndex = entries.find { it.name == "resources.index" }
            if (resIndex != null) {
                val tmpFile = File.createTempFile("res", ".index")
                tmpFile.deleteOnExit()
                zip.getInputStream(resIndex).transferTo(tmpFile.outputStream())
                val resBuf = ResIndexBuf(wrapAsLEByteBuf(
                    FileChannel.open(tmpFile.toPath())
                        .map(FileChannel.MapMode.READ_ONLY, 0, tmpFile.length())
                        .order(ByteOrder.LITTLE_ENDIAN)
                ))
                val allItems = resBuf.resMap.values.flatten()
                sb.appendLine("\n--- Resources (${allItems.size} items) ---")
                val byType = allItems.groupBy { it.resType.toString() }
                byType.forEach { (type, items) ->
                    sb.appendLine("  $type: ${items.size}")
                }
            }

            // Check signing
            val mmap = FileChannel.open(file.toPath())
                .map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            val buf = wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN))
            val signBlocks = HapSignBlocks.from(buf)
            if (signBlocks != null) {
                sb.appendLine("\n--- Signing ---")
                sb.appendLine("Version: v${signBlocks.version}")
            }

            zip.close()
        } catch (e: Exception) {
            sb.appendLine("\nError: ${e.message}")
        }

        return sb.toString()
    }
}
