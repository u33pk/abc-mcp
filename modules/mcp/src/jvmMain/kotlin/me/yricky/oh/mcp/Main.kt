package me.yricky.oh.mcp

import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter

/**
 * MCP Server 主入口
 * 使用 stdio 传输，JSON-RPC 2.0 协议
 */
fun main() {
    val server = McpServer()
    server.run()
}

class McpServer {
    private val json = Json { ignoreUnknownKeys = true }
    private val tools = listOf(
        ToolDefinition(
            name = "list_abc_classes",
            description = "列出 ABC 文件中的所有类",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("abc_path") {
                        put("type", "string")
                        put("description", "ABC 文件路径")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("abc_path"))
                }
            }
        ),
        ToolDefinition(
            name = "decompile_class",
            description = "反编译 ABC 文件中的指定类",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("abc_path") {
                        put("type", "string")
                        put("description", "ABC 文件路径")
                    }
                    putJsonObject("class_name") {
                        put("type", "string")
                        put("description", "类名")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("abc_path"))
                    add(JsonPrimitive("class_name"))
                }
            }
        ),
        ToolDefinition(
            name = "search_strings",
            description = "在 ABC 文件中搜索字符串",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("abc_path") {
                        put("type", "string")
                        put("description", "ABC 文件路径")
                    }
                    putJsonObject("pattern") {
                        put("type", "string")
                        put("description", "搜索模式（正则表达式）")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("abc_path"))
                    add(JsonPrimitive("pattern"))
                }
            }
        )
    )

    fun run() {
        val reader = BufferedReader(InputStreamReader(System.`in`))
        val writer = PrintWriter(System.out, true)

        // 发送初始化响应
        val initResponse = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", JsonNull)
            putJsonObject("result") {
                putJsonObject("capabilities") {
                    putJsonObject("tools") {}
                }
                putJsonObject("serverInfo") {
                    put("name", "abcde-mcp")
                    put("version", "0.1.0")
                }
            }
        }
        writer.println(initResponse.toString())

        // 主循环
        while (true) {
            val line = reader.readLine() ?: break
            try {
                val request = json.parseToJsonElement(line).jsonObject
                val method = request["method"]?.jsonPrimitive?.content
                val id = request["id"]
                val params = request["params"]?.jsonObject

                when (method) {
                    "tools/list" -> handleToolsList(id, writer)
                    "tools/call" -> handleToolsCall(id, params, writer)
                    "ping" -> handlePing(id, writer)
                    else -> handleUnknownMethod(id, method, writer)
                }
            } catch (e: Exception) {
                // 忽略解析错误
            }
        }
    }

    private fun handleToolsList(id: JsonElement?, writer: PrintWriter) {
        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            putJsonObject("result") {
                putJsonArray("tools") {
                    for (tool in tools) {
                        addJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("inputSchema", tool.inputSchema)
                        }
                    }
                }
            }
        }
        writer.println(response.toString())
    }

    private fun handleToolsCall(id: JsonElement?, params: JsonObject?, writer: PrintWriter) {
        val toolName = params?.get("name")?.jsonPrimitive?.content
        val arguments = params?.get("arguments")?.jsonObject ?: buildJsonObject {}

        val result = when (toolName) {
            "list_abc_classes" -> handleListAbcClasses(arguments)
            "decompile_class" -> handleDecompileClass(arguments)
            "search_strings" -> handleSearchStrings(arguments)
            else -> "Unknown tool: $toolName"
        }

        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            putJsonObject("result") {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", result)
                    }
                }
            }
        }
        writer.println(response.toString())
    }

    private fun handlePing(id: JsonElement?, writer: PrintWriter) {
        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            putJsonObject("result") {}
        }
        writer.println(response.toString())
    }

    private fun handleUnknownMethod(id: JsonElement?, method: String?, writer: PrintWriter) {
        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            putJsonObject("error") {
                put("code", -32601)
                put("message", "Method not found: $method")
            }
        }
        writer.println(response.toString())
    }

    private fun handleListAbcClasses(args: JsonObject): String {
        val abcPath = args["abc_path"]?.jsonPrimitive?.content ?: return "Error: abc_path is required"
        return try {
            val file = java.io.File(abcPath)
            if (!file.exists()) return "Error: File not found: $abcPath"
            
            val buf = java.nio.channels.FileChannel.open(file.toPath())
                .map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, file.length())
            val abc = me.yricky.oh.abcd.AbcBuf(abcPath, me.yricky.oh.common.wrapAsLEByteBuf(buf))
            
            val sb = StringBuilder()
            sb.appendLine("Classes in $abcPath:")
            abc.classes.forEach { (offset, classItem) ->
                sb.appendLine("  - ${classItem.name}")
            }
            sb.toString()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun handleDecompileClass(args: JsonObject): String {
        val abcPath = args["abc_path"]?.jsonPrimitive?.content ?: return "Error: abc_path is required"
        val className = args["class_name"]?.jsonPrimitive?.content ?: return "Error: class_name is required"
        
        return try {
            val file = java.io.File(abcPath)
            if (!file.exists()) return "Error: File not found: $abcPath"
            
            val buf = java.nio.channels.FileChannel.open(file.toPath())
                .map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, file.length())
            val abc = me.yricky.oh.abcd.AbcBuf(abcPath, me.yricky.oh.common.wrapAsLEByteBuf(buf))
            
            val classItem = abc.classes.values.find { it.name == className }
                ?: return "Error: Class not found: $className"
            
            val sb = StringBuilder()
            sb.appendLine("// Decompiled class: $className")
            sb.appendLine("// Methods:")
            
            if (classItem is me.yricky.oh.abcd.cfm.AbcClass) {
                classItem.methods.forEach { method ->
                    sb.appendLine("\n// Method: ${method.name}")
                    try {
                        val code = method.codeItem
                        if (code != null) {
                            val asm = me.yricky.oh.abcd.isa.Asm(code)
                            val result = me.yricky.oh.abcd.decompiler.structure.StructuredDecompiler.decompile(asm)
                            sb.appendLine(result)
                        }
                    } catch (e: Exception) {
                        sb.appendLine("// Error decompiling method: ${e.message}")
                    }
                }
            }
            
            sb.toString()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun handleSearchStrings(args: JsonObject): String {
        val abcPath = args["abc_path"]?.jsonPrimitive?.content ?: return "Error: abc_path is required"
        val pattern = args["pattern"]?.jsonPrimitive?.content ?: return "Error: pattern is required"
        
        return try {
            val file = java.io.File(abcPath)
            if (!file.exists()) return "Error: File not found: $abcPath"
            
            val buf = java.nio.channels.FileChannel.open(file.toPath())
                .map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, file.length())
            val abc = me.yricky.oh.abcd.AbcBuf(abcPath, me.yricky.oh.common.wrapAsLEByteBuf(buf))
            
            val regex = Regex(pattern)
            val results = mutableListOf<String>()
            
            // 搜索字符串字面量
            abc.classes.forEach { (offset, classItem) ->
                if (classItem is me.yricky.oh.abcd.cfm.AbcClass) {
                    classItem.methods.forEach { method ->
                        try {
                            val code = method.codeItem
                            if (code != null) {
                                val asm = me.yricky.oh.abcd.isa.Asm(code)
                                asm.list.forEach { item ->
                                    // 检查是否有字符串参数
                                    item.ins.format.forEachIndexed { index, fmt ->
                                        if (fmt is me.yricky.oh.abcd.isa.InstFmt.SId) {
                                            val str = fmt.getString(item)
                                            if (regex.containsMatchIn(str)) {
                                                results.add("Found in ${classItem.name}.${method.name}: \"$str\"")
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // 忽略错误
                        }
                    }
                }
            }
            
            if (results.isEmpty()) {
                "No strings found matching pattern: $pattern"
            } else {
                val sb = StringBuilder()
                sb.appendLine("Found ${results.size} strings matching pattern: $pattern")
                results.forEach { sb.appendLine(it) }
                sb.toString()
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)
