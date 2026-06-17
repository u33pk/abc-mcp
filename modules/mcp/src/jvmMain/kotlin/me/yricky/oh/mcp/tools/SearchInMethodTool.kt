package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.decompiler.structure.decodeMethodName
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.abcd.isa.util.fullDisasmLine
import me.yricky.oh.mcp.session.SessionManager

class SearchInMethodTool(private val sessionManager: SessionManager) : Tool {
    override val name = "search_in_method"
    override val description = "在指定方法体内按正则搜索，返回匹配的反汇编行及上下文（不触发完整反编译，适用于超大方法）"
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
            putJsonObject("pattern") {
                put("type", "string")
                put("description", "正则表达式搜索模式")
            }
            putJsonObject("context_lines") {
                put("type", "integer")
                put("description", "匹配行前后展示的上下文行数，默认 3")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("path"))
            add(JsonPrimitive("class_name"))
            add(JsonPrimitive("method_name"))
            add(JsonPrimitive("pattern"))
        }
    }

    override fun execute(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.content ?: return "Error: path is required"
        val className = args["class_name"]?.jsonPrimitive?.content ?: return "Error: class_name is required"
        val methodName = args["method_name"]?.jsonPrimitive?.content ?: return "Error: method_name is required"
        val pattern = args["pattern"]?.jsonPrimitive?.content ?: return "Error: pattern is required"
        val contextLines = args["context_lines"]?.jsonPrimitive?.intOrNull ?: 3

        val abc = sessionManager.getOrOpen(path)
        val classItem = abc.findClassByName(className)
            ?: return "Error: Class not found: $className"

        if (classItem !is AbcClass) return "Error: $className is not a full class definition"

        val method = classItem.methods.find { m ->
            val decoded = if (m is me.yricky.oh.abcd.cfm.AbcMethod) decodeMethodName(m) else m.name
            decoded == methodName || m.name == methodName
        } ?: return "Error: Method not found: $methodName"

        val code = method.codeItem ?: return "Error: No code (abstract/native method)"

        val regex = try {
            Regex(pattern)
        } catch (e: Exception) {
            return "Error: Invalid regex pattern: ${e.message}"
        }

        val asm = Asm(code)
        val lines = asm.list.map { it.fullDisasmLine() }
        val matches = lines.mapIndexedNotNull { index, line ->
            if (regex.containsMatchIn(line)) index else null
        }

        val sb = StringBuilder()
        sb.appendLine("// Search '$pattern' in $className.$methodName")
        sb.appendLine("// Total instructions: ${lines.size}")
        sb.appendLine("// Matches: ${matches.size}")
        sb.appendLine()

        if (matches.isEmpty()) {
            sb.appendLine("// No matches found")
            return sb.toString().trim()
        }

        val maxTotalLines = 200
        var printedLines = 0

        matches.forEachIndexed { matchIdx, matchLineIdx ->
            val start = (matchLineIdx - contextLines).coerceAtLeast(0)
            val end = (matchLineIdx + contextLines).coerceAtMost(lines.size - 1)
            val matchLineCount = end - start + 1

            if (printedLines + matchLineCount > maxTotalLines) {
                sb.appendLine("// ... remaining matches omitted due to output limit")
                return sb.toString().trim()
            }

            val offsetHex = asm.list[matchLineIdx].codeOffset.toString(16).padStart(4, '0')
            sb.appendLine("--- Match ${matchIdx + 1} at offset 0x$offsetHex ---")
            for (i in start..end) {
                val prefix = if (i == matchLineIdx) ">>> " else "    "
                sb.appendLine("$prefix${lines[i]}")
            }
            sb.appendLine()
            printedLines += matchLineCount + 2 // 包括分隔行
        }

        return sb.toString().trim()
    }
}
