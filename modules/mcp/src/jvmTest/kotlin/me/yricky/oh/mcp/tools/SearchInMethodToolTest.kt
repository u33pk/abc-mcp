package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.yricky.oh.mcp.session.SessionManager
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchInMethodToolTest {

    private val sessionManager = SessionManager()
    private val registry = ToolRegistry(sessionManager)

    private val abcDir = "/home/orz/project/unitTest/out"
    private val kazumiAbc = "/home/orz/project/unitTest/kazumi/ets/modules.abc"

    private fun callTool(name: String, vararg pairs: Pair<String, JsonElement>): String {
        val args = kotlinx.serialization.json.buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
        return registry.callTool(name, args)
    }

    @Test
    fun testSearchInMethod() {
        val result = callTool(
            "search_in_method",
            "path" to JsonPrimitive("$abcDir/load_string_dynamic.abc"),
            "class_name" to JsonPrimitive("_GLOBAL"),
            "method_name" to JsonPrimitive("foo"),
            "pattern" to JsonPrimitive("lda.str"),
            "context_lines" to JsonPrimitive(2)
        )
        println(result)
        assertTrue("Should find matches", result.contains("Match 1"))
        assertTrue("Should show disassembly context", result.contains("["))
    }

    @Test
    fun testSearchStringInLargeMethod() {
        // 在超大方法中搜索字符串，验证不触发完整反编译也能工作
        val result = callTool(
            "search_in_method",
            "path" to JsonPrimitive(kazumiAbc),
            "class_name" to JsonPrimitive("com.predidit.kazumi/entry@flutter_inappwebview_ohos/ets/components/plugin/webview/in_app_webview/InAppWebView"),
            "method_name" to JsonPrimitive("setSettings"),
            "pattern" to JsonPrimitive("userAgent"),
            "context_lines" to JsonPrimitive(3)
        )
        println(result)
        assertTrue("Should complete without OOM", result.contains("Search '"))
        // 可能命中也可能不命中，取决于实际内容；至少不应报错
        assertTrue("Should contain total instructions", result.contains("Total instructions:"))
    }

    @Test
    fun testSearchInvalidRegex() {
        val result = callTool(
            "search_in_method",
            "path" to JsonPrimitive(kazumiAbc),
            "class_name" to JsonPrimitive("com.predidit.kazumi/entry@audio_service/ets/AudioService"),
            "method_name" to JsonPrimitive("get"),
            "pattern" to JsonPrimitive("[invalid")
        )
        println(result)
        assertTrue("Should return regex error", result.startsWith("Error:"))
    }
}
