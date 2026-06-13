package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.mcp.session.SessionManager

class GetXrefsToFieldTool(private val sessionManager: SessionManager) : Tool {
    override val name = "get_xrefs_to_field"
    override val description = "查找 ABC 文件中指定字段的读取者和写入者（交叉引用，类内启发式 + 名字兜底）"
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "ABC 文件路径")
            }
            putJsonObject("class_name") {
                put("type", "string")
                put("description", "字段所在类名（用于类内启发式索引）")
            }
            putJsonObject("field_name") {
                put("type", "string")
                put("description", "字段名")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("path"))
            add(JsonPrimitive("class_name"))
            add(JsonPrimitive("field_name"))
        }
    }

    override fun execute(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.content ?: return "Error: path is required"
        val className = args["class_name"]?.jsonPrimitive?.content ?: return "Error: class_name is required"
        val fieldName = args["field_name"]?.jsonPrimitive?.content ?: return "Error: field_name is required"

        val index = sessionManager.getXRefIndex(path)
        val readers = index.getFieldReaders(className, fieldName)
        val writers = index.getFieldWriters(className, fieldName)
        val possibleReaders = index.getNameBasedFieldReaders(fieldName)
            .filter { it.callerClass != className }
        val possibleWriters = index.getNameBasedFieldWriters(fieldName)
            .filter { it.callerClass != className }

        val sb = StringBuilder()
        sb.appendLine("// Cross references to $className.$fieldName")
        sb.appendLine("// Readers: ${readers.size}")
        sb.appendLine("// Writers: ${writers.size}")
        sb.appendLine()

        if (readers.isEmpty() && writers.isEmpty()) {
            sb.appendLine("// No class-internal references found for '$fieldName'")
        } else {
            if (readers.isNotEmpty()) {
                sb.appendLine("// Readers (likely 'this.$fieldName'):")
                readers.forEachIndexed { idx, loc ->
                    sb.appendLine("${idx + 1}. ${loc.callerFullName} (offset 0x${loc.codeOffset.toString(16)})")
                }
                sb.appendLine()
            }
            if (writers.isNotEmpty()) {
                sb.appendLine("// Writers (likely 'this.$fieldName'):")
                writers.forEachIndexed { idx, loc ->
                    sb.appendLine("${idx + 1}. ${loc.callerFullName} (offset 0x${loc.codeOffset.toString(16)})")
                }
                sb.appendLine()
            }
        }

        if (possibleReaders.isNotEmpty() || possibleWriters.isNotEmpty()) {
            sb.appendLine("// Possible same-name field accesses in other classes:")
            possibleReaders.forEachIndexed { idx, loc ->
                sb.appendLine("${idx + 1}. ${loc.callerFullName} (offset 0x${loc.codeOffset.toString(16)}) [read]")
            }
            possibleWriters.forEachIndexed { idx, loc ->
                sb.appendLine("${possibleReaders.size + idx + 1}. ${loc.callerFullName} (offset 0x${loc.codeOffset.toString(16)}) [write]")
            }
        }

        return sb.toString().trim()
    }
}
