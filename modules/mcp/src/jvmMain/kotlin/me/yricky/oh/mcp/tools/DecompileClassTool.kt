package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.decompiler.structure.StructuredDecompiler
import me.yricky.oh.abcd.decompiler.structure.decodeMethodName
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.mcp.session.SessionManager

class DecompileClassTool(private val sessionManager: SessionManager) : Tool {
    override val name = "decompile_class"
    override val description = "反编译 ABC 文件中指定类的所有方法"
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

        val sb = StringBuilder()
        sb.appendLine("// Class: ${classItem.name}")

        classItem.methods.forEach { method ->
            val methodName = decodeMethodName(method)
            sb.appendLine("\n// Method: $methodName")
            try {
                val code = method.codeItem
                if (code != null) {
                    val asm = Asm(code)
                    val result = StructuredDecompiler.decompile(asm)
                    sb.appendLine(result)
                } else {
                    sb.appendLine("// No code (abstract/native)")
                }
            } catch (e: Exception) {
                sb.appendLine("// Error: ${e.message}")
            }
        }

        return sb.toString()
    }
}
