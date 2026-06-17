package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.decompiler.structure.decodeMethodName
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.abcd.isa.util.fullDisasmLine
import me.yricky.oh.mcp.session.SessionManager

class DisassembleMethodTool(private val sessionManager: SessionManager) : Tool {
    override val name = "disassemble_method"
    override val description = "获取方法的字节码反汇编（默认最多 200 行，支持 offset/limit 分页）"
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
            putJsonObject("offset") {
                put("type", "integer")
                put("description", "起始指令索引（0-based），默认 0")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "返回指令数上限，默认 200")
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
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 200
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
        val total = asm.list.size
        val sb = StringBuilder()
        sb.appendLine("// Disassembly: $className.$methodName")
        sb.appendLine("// Instructions: $total")

        if (offset > 0 || limit < total) {
            sb.appendLine("// Showing [$offset, ${(offset + limit).coerceAtMost(total)}) / $total")
        }

        asm.list.drop(offset).take(limit).forEach { item ->
            sb.appendLine(item.fullDisasmLine())
        }

        if (offset + limit < total) {
            sb.appendLine("// ... ${total - offset - limit} more instructions. Use offset=${offset + limit} for next page.")
        }

        return sb.toString()
    }
}
