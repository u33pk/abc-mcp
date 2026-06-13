package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.mcp.session.SessionManager

class GetXrefsToClassTool(private val sessionManager: SessionManager) : Tool {
    override val name = "get_xrefs_to_class"
    override val description = "查找 ABC 文件中指定类被实例化的位置（交叉引用）"
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
        }
        putJsonArray("required") {
            add(JsonPrimitive("path"))
            add(JsonPrimitive("class_name"))
        }
    }

    override fun execute(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.content ?: return "Error: path is required"
        val className = args["class_name"]?.jsonPrimitive?.content ?: return "Error: class_name is required"

        val abc = sessionManager.getOrOpen(path)
        val classItem = abc.classes.values.find { it.name == className }
            ?: return "Error: Class not found: $className"

        if (classItem !is AbcClass) return "Error: $className is not a full class definition"

        val index = sessionManager.getXRefIndex(path)
        val instantiations = index.getInstantiations(className)

        val sb = StringBuilder()
        sb.appendLine("// Cross references to $className")
        sb.appendLine("// Total instantiations: ${instantiations.size}")
        sb.appendLine()

        if (instantiations.isEmpty()) {
            sb.appendLine("// No instantiations found")
        } else {
            instantiations.forEachIndexed { idx, loc ->
                sb.appendLine("${idx + 1}. ${loc.callerFullName} (offset 0x${loc.codeOffset.toString(16)})")
            }
        }

        return sb.toString().trim()
    }
}
