package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.yricky.oh.mcp.session.SessionManager
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceCompatSmokeTest {

    private val sessionManager = SessionManager()
    private val registry = ToolRegistry(sessionManager)

    private val newResIndex = "/home/orz/project/unitTest/kazumi/resources.index"
    private val hapFile = "/home/orz/project/unitTest/kazumi/Kazumi_ohos_2.1.5_unsigned.hap"

    private fun callTool(name: String, vararg pairs: Pair<String, JsonElement>): String {
        val args = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
        return registry.callTool(name, args)
    }

    @Test
    fun testSearchResourcesOnNewIndex() {
        val result = callTool(
            "search_resources",
            "path" to JsonPrimitive(newResIndex),
            "pattern" to JsonPrimitive("app_name")
        )
        println(result)
        assertTrue("Should find app_name", result.contains("app_name"))
        assertTrue("Should contain value Kazumi", result.contains("Kazumi"))
    }

    @Test
    fun testResolveResourceOnNewIndex() {
        val result = callTool(
            "resolve_resource",
            "path" to JsonPrimitive(newResIndex),
            "reference" to JsonPrimitive("\$string:app_name")
        )
        println(result)
        assertTrue("Should resolve app_name", result.contains("app_name"))
        assertTrue("Should contain Kazumi", result.contains("Kazumi"))
    }

    @Test
    fun testOpenHapNoResourceError() {
        val result = callTool("open_hap", "path" to JsonPrimitive(hapFile))
        println(result)
        assertTrue("Should list resources", result.contains("Resources"))
        assertTrue("Should not report parsing error", !result.contains("Error parsing resources.index"))
    }
}
