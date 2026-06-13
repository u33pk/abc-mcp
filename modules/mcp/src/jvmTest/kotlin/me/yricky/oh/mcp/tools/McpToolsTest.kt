package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.mcp.session.SessionManager
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolsTest {

    private val sessionManager = SessionManager()
    private val registry = ToolRegistry(sessionManager)

    private val abcDir = "/Users/vv/project/unitTest/out"
    private val hapFile = "/Users/vv/project/unitTest/kazumi/Kazumi_ohos_2.1.5_unsigned.hap"
    private val resIndexFile = "/Users/vv/project/unitTest/kazumi/test_resources.index"

    private fun callTool(name: String, vararg pairs: Pair<String, JsonElement>): String {
        val args = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
        return registry.callTool(name, args)
    }

    @Test
    fun testOpenAbc() {
        val result = callTool("open_abc", "path" to JsonPrimitive("$abcDir/emptyobj.abc"))
        println(result)
        assertTrue("Should contain opened path", result.contains("Opened:"))
        assertTrue("Should contain version", result.contains("Version:"))
        assertTrue("Should contain class count", result.contains("Classes:"))
        assertTrue("Should contain method count", result.contains("Methods:"))
    }

    @Test
    fun testOpenAbcNotFound() {
        val result = callTool("open_abc", "path" to JsonPrimitive("$abcDir/nonexistent.abc"))
        println(result)
        assertTrue("Should return error for missing file", result.startsWith("Error:"))
    }

    @Test
    fun testListClasses() {
        val result = callTool("list_classes", "path" to JsonPrimitive("$abcDir/emptyobj.abc"))
        println(result)
        assertTrue("Should list _GLOBAL class", result.contains("_GLOBAL"))
        assertTrue("Should contain header", result.contains("Classes in"))
    }

    @Test
    fun testListClassesWithFilter() {
        val result = callTool(
            "list_classes",
            "path" to JsonPrimitive("$abcDir/call_dynamic.abc"),
            "filter" to JsonPrimitive("_GLOBAL")
        )
        println(result)
        assertTrue("Filter should match _GLOBAL", result.contains("_GLOBAL"))
    }

    @Test
    fun testGetClassDetail() {
        val result = callTool(
            "get_class_detail",
            "path" to JsonPrimitive("$abcDir/emptyobj.abc"),
            "class_name" to JsonPrimitive("_GLOBAL")
        )
        println(result)
        assertTrue("Should contain class name", result.contains("Class: _GLOBAL"))
        assertTrue("Should contain methods", result.contains("Methods:"))
    }

    @Test
    fun testGetClassDetailNotFound() {
        val result = callTool(
            "get_class_detail",
            "path" to JsonPrimitive("$abcDir/emptyobj.abc"),
            "class_name" to JsonPrimitive("NotExist")
        )
        println(result)
        assertTrue("Should return error for missing class", result.startsWith("Error:"))
    }

    @Test
    fun testDecompileClass() {
        val result = callTool(
            "decompile_class",
            "path" to JsonPrimitive("$abcDir/emptyobj.abc"),
            "class_name" to JsonPrimitive("_GLOBAL")
        )
        println(result)
        assertTrue("Should contain class header", result.contains("// Class: _GLOBAL"))
        assertTrue("Should contain decompiled function", result.contains("function"))
    }

    @Test
    fun testDecompileMethod() {
        val result = callTool(
            "decompile_method",
            "path" to JsonPrimitive("$abcDir/call_dynamic.abc"),
            "class_name" to JsonPrimitive("_GLOBAL"),
            "method_name" to JsonPrimitive("func")
        )
        println(result)
        assertTrue("Should contain method header", result.contains("// Method:"))
        assertTrue("Should contain decompiled func", result.contains("function func("))
    }

    @Test
    fun testDecompileMethodNotFound() {
        val result = callTool(
            "decompile_method",
            "path" to JsonPrimitive("$abcDir/call_dynamic.abc"),
            "class_name" to JsonPrimitive("_GLOBAL"),
            "method_name" to JsonPrimitive("not_exist")
        )
        println(result)
        assertTrue("Should return error for missing method", result.startsWith("Error:"))
    }

    @Test
    fun testSearchStrings() {
        val result = callTool(
            "search_strings",
            "path" to JsonPrimitive("$abcDir/call_dynamic.abc"),
            "pattern" to JsonPrimitive("func")
        )
        println(result)
        assertTrue("Should report found strings", result.contains("Found"))
        assertTrue("Should contain matching string", result.contains("\"func\""))
    }

    @Test
    fun testSearchStringsNoMatch() {
        val result = callTool(
            "search_strings",
            "path" to JsonPrimitive("$abcDir/call_dynamic.abc"),
            "pattern" to JsonPrimitive("xyz123notfound")
        )
        println(result)
        assertTrue("Should report no match", result.contains("No strings found"))
    }

    @Test
    fun testDisassembleMethod() {
        val result = callTool(
            "disassemble_method",
            "path" to JsonPrimitive("$abcDir/call_dynamic.abc"),
            "class_name" to JsonPrimitive("_GLOBAL"),
            "method_name" to JsonPrimitive("func")
        )
        println(result)
        assertTrue("Should contain disassembly header", result.contains("// Disassembly:"))
        assertTrue("Should contain instruction count", result.contains("// Instructions:"))
    }

    @Test
    fun testGetMethodInfo() {
        val result = callTool(
            "get_method_info",
            "path" to JsonPrimitive("$abcDir/call_dynamic.abc"),
            "class_name" to JsonPrimitive("_GLOBAL"),
            "method_name" to JsonPrimitive("func")
        )
        println(result)
        assertTrue("Should contain method name", result.contains("Method:"))
        assertTrue("Should contain args", result.contains("Args:"))
        assertTrue("Should contain has code flag", result.contains("Has code:"))
    }

    @Test
    fun testOpenHap() {
        val result = callTool("open_hap", "path" to JsonPrimitive(hapFile))
        println(result)
        assertTrue("Should contain HAP header", result.contains("=== HAP File:"))
        assertTrue("Should contain bundle name", result.contains("Bundle:"))
        assertTrue("Should list ABC files", result.contains("ABC Files"))
        assertTrue("Should list resources", result.contains("Resources"))
    }

    @Test
    fun testGetHapManifest() {
        val result = callTool("get_hap_manifest", "path" to JsonPrimitive(hapFile))
        println(result)
        assertTrue("Should contain bundle name", result.contains("bundleName: com.predidit.kazumi"))
        assertTrue("Should contain version name", result.contains("versionName: 2.1.5"))
    }

    @Test
    fun testGetObfuscationMapNotFound() {
        val result = callTool("get_obfuscation_map", "path" to JsonPrimitive(hapFile))
        println(result)
        assertTrue("Should report missing obfuscation map", result.contains("obfuscation.map not found"))
    }

    @Test
    fun testSearchResources() {
        val result = callTool(
            "search_resources",
            "path" to JsonPrimitive(resIndexFile),
            "pattern" to JsonPrimitive("app_name")
        )
        println(result)
        assertTrue("Should find app_name resource", result.contains("app_name"))
    }

    @Test
    fun testSearchResourcesNoMatch() {
        val result = callTool(
            "search_resources",
            "path" to JsonPrimitive(resIndexFile),
            "pattern" to JsonPrimitive("xyz123notfound")
        )
        println(result)
        assertTrue("Should report no match", result.contains("0 results"))
    }

    @Test
    fun testResolveResource() {
        val result = callTool(
            "resolve_resource",
            "path" to JsonPrimitive(resIndexFile),
            "reference" to JsonPrimitive("\$string:app_name")
        )
        println(result)
        assertTrue("Should resolve app_name", result.contains("Name: app_name"))
        assertTrue("Should contain type info", result.contains("Type:"))
        assertTrue("Should contain resolved value", result.contains("Kazumi"))
    }
}
