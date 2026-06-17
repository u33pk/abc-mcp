package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.cfm.AbcMethod
import me.yricky.oh.abcd.decompiler.structure.decodeMethodName
import me.yricky.oh.mcp.session.SessionManager

class GetXrefsToMethodTool(private val sessionManager: SessionManager) : Tool {
    override val name = "get_xrefs_to_method"
    override val description = "查找 ABC 文件中指定方法的调用者（交叉引用）"
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
                put("description", "方法名（解码后的名称，如 'loop', 'constructor'）")
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
        val classItem = abc.findClassByName(className)
            ?: return "Error: Class not found: $className"

        if (classItem !is AbcClass) return "Error: $className is not a full class definition"

        val method = classItem.methods.find { m ->
            val decoded = if (m is AbcMethod) decodeMethodName(m) else m.name
            decoded == methodName || m.name == methodName
        } ?: return "Error: Method not found: $methodName"

        if (method !is AbcMethod) return "Error: $methodName is not a concrete method"

        val index = sessionManager.getXRefIndex(path)
        val callers = index.getCallers(method)
        val decodedName = decodeMethodName(method)

        val sb = StringBuilder()
        sb.appendLine("// Cross references to $className.$decodedName")
        sb.appendLine("// Total callers: ${callers.size}")
        sb.appendLine()

        if (callers.isEmpty()) {
            sb.appendLine("// No callers found")
        } else {
            callers.forEachIndexed { idx, loc ->
                sb.appendLine("${idx + 1}. ${loc.callerFullName} (offset 0x${loc.codeOffset.toString(16)})")
            }
        }

        return sb.toString().trim()
    }
}
