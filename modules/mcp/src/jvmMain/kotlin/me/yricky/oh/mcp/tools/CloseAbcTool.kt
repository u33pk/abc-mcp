package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.yricky.oh.mcp.session.SessionManager

class CloseAbcTool(private val sessionManager: SessionManager) : Tool {
    override val name = "close_abc"
    override val description = "关闭指定路径的 ABC 会话并释放其缓存的交叉引用/类层次索引，降低内存占用。"
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "ABC 文件路径")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("path"))
        }
    }

    override fun execute(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.content ?: return "Error: path is required"
        return if (sessionManager.close(path)) {
            "Closed session: $path"
        } else {
            "Error: No open session for path: $path"
        }
    }
}
