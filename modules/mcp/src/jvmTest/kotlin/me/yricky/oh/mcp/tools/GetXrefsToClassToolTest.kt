package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.mcp.session.SessionManager
import org.junit.Assert.assertTrue
import org.junit.Test

class GetXrefsToClassToolTest {

    private val sessionManager = SessionManager()
    private val registry = ToolRegistry(sessionManager)

    private val kazumiAbc = "/home/orz/project/unitTest/kazumi/ets/modules.abc"

    private fun callTool(name: String, vararg pairs: Pair<String, JsonElement>): String {
        val args = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
        return registry.callTool(name, args)
    }

    @Test
    fun testGetXrefsToClass() {
        val abc = sessionManager.getOrOpen(kazumiAbc)
        val targetClass = abc.classes.values.filterIsInstance<AbcClass>()
            .find { it.name.endsWith("/WebViewChannelDelegate") }
        assertTrue("Should find WebViewChannelDelegate class", targetClass != null)

        val result = callTool(
            "get_xrefs_to_class",
            "path" to JsonPrimitive(kazumiAbc),
            "class_name" to JsonPrimitive(targetClass!!.name)
        )
        println(result)
        assertTrue("Should report total instantiations", result.contains("Total instantiations:"))
        assertTrue("Should report total instanceof checks", result.contains("Total instanceof checks:"))
        assertTrue("Should list at least one instantiation", result.contains("1."))
    }

    @Test
    fun testClassNotFound() {
        val result = callTool(
            "get_xrefs_to_class",
            "path" to JsonPrimitive(kazumiAbc),
            "class_name" to JsonPrimitive("com.example.NonExistentClass")
        )
        println(result)
        assertTrue("Should return error for missing class", result.startsWith("Error:"))
    }
}
