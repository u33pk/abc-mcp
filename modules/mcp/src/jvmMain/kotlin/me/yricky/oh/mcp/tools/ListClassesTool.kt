package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.mcp.session.SessionManager

class ListClassesTool(private val sessionManager: SessionManager) : Tool {
    override val name = "list_classes"
    override val description = "列出 ABC 文件中的所有类名，支持正则过滤"
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "ABC 文件路径")
            }
            putJsonObject("filter") {
                put("type", "string")
                put("description", "可选的正则过滤表达式")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("path")) }
    }

    override fun execute(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.content ?: return "Error: path is required"
        val filter = args["filter"]?.jsonPrimitive?.content
        val abc = sessionManager.getOrOpen(path)

        val classes = abc.classes.values
        val filtered = if (filter != null) {
            val regex = Regex(filter)
            classes.filter { regex.containsMatchIn(it.name) }
        } else {
            classes.toList()
        }

        val sb = StringBuilder()
        sb.appendLine("Classes in $path (${filtered.size}/${classes.size}):")
        filtered.forEach { sb.appendLine("  ${it.name}") }
        return sb.toString()
    }
}
