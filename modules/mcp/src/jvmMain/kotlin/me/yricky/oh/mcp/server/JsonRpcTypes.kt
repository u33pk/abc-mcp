package me.yricky.oh.mcp.server

import kotlinx.serialization.json.*

data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null
)

object JsonRpcResponse {
    fun success(id: JsonElement?, result: JsonObject): String {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id) else put("id", JsonNull)
            put("result", result)
        }.toString()
    }

    fun error(id: JsonElement?, code: Int, message: String): String {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id) else put("id", JsonNull)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }.toString()
    }

    fun textResult(id: JsonElement?, text: String): String {
        return success(id, buildJsonObject {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
        })
    }
}
