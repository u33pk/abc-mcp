package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.decompiler.structure.decodeMethodName
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.abcd.isa.asmName
import me.yricky.oh.mcp.session.SessionManager

class DisassembleMethodTool(private val sessionManager: SessionManager) : Tool {
    override val name = "disassemble_method"
    override val description = "获取方法的字节码反汇编"
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
            val decoded = if (m is me.yricky.oh.abcd.cfm.AbcMethod) decodeMethodName(m) else m.name
            decoded == methodName || m.name == methodName
        } ?: return "Error: Method not found: $methodName"

        val code = method.codeItem ?: return "Error: No code (abstract/native method)"

        val asm = Asm(code)
        val sb = StringBuilder()
        sb.appendLine("// Disassembly: $className.$methodName")
        sb.appendLine("// Instructions: ${asm.list.size}")

        asm.list.forEach { item ->
            sb.appendLine("[${item.codeOffset}] ${item.asmName}")
        }

        return sb.toString()
    }
}
