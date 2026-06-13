package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.JsonObject
import me.yricky.oh.mcp.session.SessionManager

class ToolRegistry(private val sessionManager: SessionManager) {
    private val tools = mutableMapOf<String, Tool>()

    init {
        // ABC tools
        register(OpenAbcTool(sessionManager))
        register(ListClassesTool(sessionManager))
        register(GetClassDetailTool(sessionManager))
        register(DecompileClassTool(sessionManager))
        register(DecompileMethodTool(sessionManager))
        register(SearchStringsTool(sessionManager))
        register(DisassembleMethodTool(sessionManager))
        register(GetMethodInfoTool(sessionManager))
        register(GetXrefsToMethodTool(sessionManager))
        register(GetXrefsToFieldTool(sessionManager))
        register(SearchInMethodTool(sessionManager))
        // HAP tools
        register(OpenHapTool())
        register(GetHapManifestTool())
        register(GetObfuscationMapTool())
        // Resource tools
        register(SearchResourcesTool())
        register(ResolveResourceTool())
    }

    private fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun listTools(): List<Tool> = tools.values.toList()

    fun callTool(name: String, args: JsonObject): String {
        val tool = tools[name] ?: return "Error: Unknown tool: $name"
        return try {
            tool.execute(args)
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
