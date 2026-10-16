package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.yricky.oh.mcp.session.SessionManager

class ListSessionsTool(private val sessionManager: SessionManager) : Tool {
    override val name = "list_sessions"
    override val description = "列出当前所有打开的 ABC 会话（路径、类数量、已缓存的索引），帮助掌握内存占用情况。"
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }

    override fun execute(args: JsonObject): String {
        val sessions = sessionManager.listSessions()
        if (sessions.isEmpty()) return "No open sessions."

        return buildString {
            appendLine("Open sessions: ${sessions.size}")
            for (s in sessions) {
                appendLine("- ${s.path} (classes=${s.classCount}, xref=${s.hasXRefIndex}, hierarchy=${s.hasHierarchyIndex})")
            }
        }
    }
}
