package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.decompiler.structure.StructuredDecompiler
import me.yricky.oh.abcd.decompiler.structure.decodeMethodName
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.mcp.session.SessionManager

class DecompileMethodTool(private val sessionManager: SessionManager) : Tool {
    override val name = "decompile_method"
    override val description = "反编译 ABC 文件中指定类的指定方法。超大方法（>1000指令）自动返回摘要+反汇编片段，可通过 offset/limit 分页浏览。"
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
            putJsonObject("offset") {
                put("type", "integer")
                put("description", "起始行号（0-based），用于分页浏览大方法，默认 0")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "返回行数上限，默认 100")
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
        val offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 0
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: StructuredDecompiler.MAX_DISPLAY_OUTPUT_LINES
        val abc = sessionManager.getOrOpen(path)

        val classItem = abc.findClassByName(className)
            ?: return "Error: Class not found: $className. Use format: entry/src/main/ets/pages/ClassName"

        if (classItem !is AbcClass) return "Error: $className is not a full class definition"

        val method = classItem.methods.find { m ->
            val decoded = if (m is me.yricky.oh.abcd.cfm.AbcMethod) decodeMethodName(m) else m.name
            decoded == methodName || m.name == methodName
        } ?: return "Error: Method not found: $methodName"

        val sb = StringBuilder()
        val decodedName = if (method is me.yricky.oh.abcd.cfm.AbcMethod) decodeMethodName(method) else method.name
        sb.appendLine("// Method: $decodedName")

        try {
            val code = method.codeItem
            if (code != null) {
                val asm = Asm(code)
                sb.appendLine("// Instructions: ${asm.list.size}")
                val result = StructuredDecompiler.decompile(asm, offset, limit)
                sb.appendLine(result)
            } else {
                sb.appendLine("// No code (abstract/native)")
            }
        } catch (e: Exception) {
            sb.appendLine("// Error: ${e.message}")
        }

        return sb.toString()
    }
}
