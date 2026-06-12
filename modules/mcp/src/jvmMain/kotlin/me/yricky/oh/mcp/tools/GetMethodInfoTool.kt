package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.cfm.AbcMethod
import me.yricky.oh.abcd.cfm.argsStr
import me.yricky.oh.abcd.decompiler.structure.decodeMethodName
import me.yricky.oh.mcp.session.SessionManager

class GetMethodInfoTool(private val sessionManager: SessionManager) : Tool {
    override val name = "get_method_info"
    override val description = "获取方法的详细信息（参数名、行号、调试信息）"
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "ABC 文件路径")
            }
            putJsonObject("class_name") {
                put("type", "string")
                put("description", "类名")
            }
            putJsonObject("method_name") {
                put("type", "string")
                put("description", "方法名（解码后的名称）")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("path"))
            add(JsonPrimitive("class_name"))
            add(JsonPrimitive("method_name"))
        }
    }

    override fun execute(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.content ?: return "Error: path is required"
        val className = args["class_name"]?.jsonPrimitive?.content ?: return "Error: class_name is required"
        val methodName = args["method_name"]?.jsonPrimitive?.content ?: return "Error: method_name is required"
        val abc = sessionManager.getOrOpen(path)

        val classItem = abc.classes.values.find { it.name == className }
            ?: return "Error: Class not found: $className"

        if (classItem !is AbcClass) return "Error: $className is not a full class definition"

        val method = classItem.methods.find { m ->
            val decoded = if (m is AbcMethod) decodeMethodName(m) else m.name
            decoded == methodName || m.name == methodName
        } ?: return "Error: Method not found: $methodName"

        val sb = StringBuilder()
        val decodedName = if (method is AbcMethod) decodeMethodName(method) else method.name
        sb.appendLine("Method: $decodedName")
        sb.appendLine("Raw name: ${method.name}")
        sb.appendLine("Args: ${method.argsStr()}")
        sb.appendLine("Has code: ${method.codeItem != null}")

        if (method is AbcMethod) {
            val dbgInfo = method.data.filterIsInstance<me.yricky.oh.abcd.cfm.MethodTag.DbgInfo>().firstOrNull()
            if (dbgInfo != null) {
                sb.appendLine("Debug info:")
                sb.appendLine("  Line start: ${dbgInfo.info.lineStart}")
                sb.appendLine("  Params: ${dbgInfo.info.params}")
            } else {
                sb.appendLine("Debug info: none")
            }
        }

        return sb.toString()
    }
}
