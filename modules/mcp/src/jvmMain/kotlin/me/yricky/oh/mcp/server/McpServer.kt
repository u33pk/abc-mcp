package me.yricky.oh.mcp.server

import kotlinx.serialization.json.*
import me.yricky.oh.mcp.session.SessionManager
import me.yricky.oh.mcp.tools.ToolRegistry
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter

class McpServer {
    private val json = Json { ignoreUnknownKeys = true }
    private val sessionManager = SessionManager()
    private val toolRegistry = ToolRegistry(sessionManager)

    fun run() {
        val reader = BufferedReader(InputStreamReader(System.`in`))
        val writer = PrintWriter(System.out, true)

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue

            try {
                val request = json.parseToJsonElement(line).jsonObject
                val method = request["method"]?.jsonPrimitive?.content
                val id = request["id"]
                val params = request["params"]?.jsonObject

                val response = when (method) {
                    "initialize" -> handleInitialize(id, params)
                    "notifications/initialized" -> null  // notification, no response
                    "tools/list" -> handleToolsList(id)
                    "tools/call" -> handleToolsCall(id, params)
                    "ping" -> JsonRpcResponse.success(id ?: JsonNull, buildJsonObject {})
                    else -> JsonRpcResponse.error(id, -32601, "Method not found: $method")
                }

                if (response != null) {
                    writer.println(response)
                }
            } catch (e: Exception) {
                // Try to extract id from the raw line for error response
                val id = try {
                    json.parseToJsonElement(line).jsonObject["id"]
                } catch (_: Exception) { null }
                writer.println(JsonRpcResponse.error(id, -32700, "Parse error: ${e.message}"))
            }
        }
    }

    private fun handleInitialize(id: JsonElement?, params: JsonObject?): String {
        return JsonRpcResponse.success(id, buildJsonObject {
            putJsonObject("capabilities") {
                putJsonObject("tools") {
                    put("listChanged", false)
                }
            }
            putJsonObject("serverInfo") {
                put("name", "abcde-mcp")
                put("version", "0.1.0")
            }
            put("protocolVersion", "2024-11-05")
        })
    }

    private fun handleToolsList(id: JsonElement?): String {
        return JsonRpcResponse.success(id, buildJsonObject {
            putJsonArray("tools") {
                for (tool in toolRegistry.listTools()) {
                    addJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("inputSchema", tool.inputSchema)
                    }
                }
            }
        })
    }

    private fun handleToolsCall(id: JsonElement?, params: JsonObject?): String {
        val toolName = params?.get("name")?.jsonPrimitive?.content
            ?: return JsonRpcResponse.error(id, -32602, "Missing tool name")
        val arguments = params["arguments"]?.jsonObject ?: buildJsonObject {}

        val result = toolRegistry.callTool(toolName, arguments)
        return JsonRpcResponse.textResult(id, result)
    }
}
