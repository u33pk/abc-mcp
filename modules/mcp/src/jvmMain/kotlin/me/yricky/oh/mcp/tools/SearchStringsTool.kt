package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.isa.InstFmt
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.mcp.session.SessionManager

class SearchStringsTool(private val sessionManager: SessionManager) : Tool {
    override val name = "search_strings"
    override val description = "在 ABC 文件中搜索字符串常量（支持正则表达式）"
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "ABC 文件路径")
            }
            putJsonObject("pattern") {
                put("type", "string")
                put("description", "搜索模式（正则表达式）")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("path"))
            add(JsonPrimitive("pattern"))
        }
    }

    override fun execute(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.content ?: return "Error: path is required"
        val pattern = args["pattern"]?.jsonPrimitive?.content ?: return "Error: pattern is required"
        val abc = sessionManager.getOrOpen(path)

        val regex = Regex(pattern)
        val results = mutableListOf<String>()

        abc.classes.forEach { (_, classItem) ->
            if (classItem is AbcClass) {
                classItem.methods.forEach { method ->
                    try {
                        val code = method.codeItem ?: return@forEach
                        val asm = Asm(code)
                        asm.list.forEach { item ->
                            item.ins.format.forEachIndexed { _, fmt ->
                                if (fmt is InstFmt.SId) {
                                    val str = fmt.getString(item)
                                    if (regex.containsMatchIn(str)) {
                                        results.add("${classItem.name}.${method.name}: \"$str\"")
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        return if (results.isEmpty()) {
            "No strings found matching: $pattern"
        } else {
            val sb = StringBuilder()
            sb.appendLine("Found ${results.size} strings matching: $pattern")
            results.take(100).forEach { sb.appendLine("  $it") }
            if (results.size > 100) sb.appendLine("  ... and ${results.size - 100} more")
            sb.toString()
        }
    }
}
