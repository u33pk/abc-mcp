package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.mcp.session.SessionManager

class OpenAbcTool(private val sessionManager: SessionManager) : Tool {
    override val name = "open_abc"
    override val description = "打开 ABC 文件并返回基本信息（版本、类数量等）"
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "ABC 文件路径")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("path")) }
    }

    override fun execute(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.content ?: return "Error: path is required"
        val abc = sessionManager.open(path)

        val sb = StringBuilder()
        sb.appendLine("Opened: $path")
        sb.appendLine("Version: ${abc.header.version}")
        sb.appendLine("Classes: ${abc.classes.size}")

        val abcClasses = abc.classes.values.filterIsInstance<AbcClass>()
        sb.appendLine("Methods: ${abcClasses.sumOf { it.methods.size }}")

        return sb.toString()
    }
}
