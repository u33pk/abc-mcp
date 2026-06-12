package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.JsonObject

interface Tool {
    val name: String
    val description: String
    val inputSchema: JsonObject

    fun execute(args: JsonObject): String
}
