package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.mcp.session.SessionManager
import org.junit.Assume
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GetClassHierarchyToolTest {

    private val sessionManager = SessionManager()
    private val registry = ToolRegistry(sessionManager)

    private val kazumiAbc = "/Users/vv/project/unitTest/kazumi/ets/modules.abc"

    private fun assumeAbc() {
        Assume.assumeTrue("Kazumi ABC not found: $kazumiAbc", File(kazumiAbc).exists())
    }

    private fun callTool(name: String, vararg pairs: Pair<String, JsonElement>): String {
        val args = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
        return registry.callTool(name, args)
    }

    @Test
    fun testGetClassHierarchy() {
        assumeAbc()
        val abc = sessionManager.getOrOpen(kazumiAbc)
        val entryAbility = abc.classes.values.filterIsInstance<AbcClass>()
            .find { it.name.endsWith("/EntryAbility") }
        assertTrue("Should find EntryAbility class", entryAbility != null)

        val result = callTool(
            "get_class_hierarchy",
            "path" to JsonPrimitive(kazumiAbc),
            "class_name" to JsonPrimitive(entryAbility!!.name)
        )
        println(result)
        assertTrue("Should report super class", result.contains("Super class:"))
        assertTrue("Should report FlutterAbility as super", result.contains("FlutterAbility"))
        assertTrue("Should report direct subclasses count", result.contains("Direct subclasses:"))
    }

    @Test
    fun testGetClassHierarchyForExternalParent() {
        assumeAbc()
        // FlutterAbility 自身也作为类出现在 ABC 中（来自外部模块），应能查到它的子类 EntryAbility
        val abc = sessionManager.getOrOpen(kazumiAbc)
        val flutterAbility = abc.classes.values.filterIsInstance<AbcClass>()
            .find { it.name.endsWith("/FlutterAbility") }
        assertTrue("Should find FlutterAbility class", flutterAbility != null)

        val result = callTool(
            "get_class_hierarchy",
            "path" to JsonPrimitive(kazumiAbc),
            "class_name" to JsonPrimitive(flutterAbility!!.name)
        )
        println(result)
        assertTrue("Should list EntryAbility as subclass", result.contains("EntryAbility"))
    }

    @Test
    fun testClassNotFound() {
        assumeAbc()
        val result = callTool(
            "get_class_hierarchy",
            "path" to JsonPrimitive(kazumiAbc),
            "class_name" to JsonPrimitive("com.example.NonExistentClass")
        )
        println(result)
        assertTrue("Should return error for missing class", result.startsWith("Error:"))
    }
}
