package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.mcp.session.SessionManager
import org.junit.Assert.*
import org.junit.Test

/**
 * 使用 HISH HAP 进行全工具集成测试
 */
class HishHapIntegrationTest {

    private val sessionManager = SessionManager()
    private val registry = ToolRegistry(sessionManager)

    private val hapFile = "/home/orz/project/unitTest/HISH/hish-unsigned.hap"

    private fun callTool(name: String, vararg pairs: Pair<String, JsonElement>): String {
        val args = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
        return registry.callTool(name, args)
    }

    // ==================== 辅助方法 ====================

    private var extractedAbcPath: String? = null

    private fun getExtractedAbcPath(): String? {
        extractedAbcPath?.let { return it }
        // open_hap 自动提取 ABC 到 HAP 同目录，从输出中获取路径
        val hapResult = callTool("open_hap", "path" to JsonPrimitive(hapFile))
        for (line in hapResult.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("-> ") && trimmed.endsWith(".abc")) {
                extractedAbcPath = trimmed.removePrefix("-> ")
                println("Using extracted ABC: $extractedAbcPath")
                return extractedAbcPath
            }
        }
        return null
    }

    private fun getFirstClassName(abcPath: String): String? {
        val result = callTool("list_classes", "path" to JsonPrimitive(abcPath))
        for (line in result.lines()) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("Classes") && !trimmed.startsWith("=") && !trimmed.startsWith("-")) {
                return trimmed
            }
        }
        return null
    }

    private fun getFirstMethodName(abcPath: String, className: String): String? {
        val result = callTool("get_class_detail", "path" to JsonPrimitive(abcPath), "class_name" to JsonPrimitive(className))
        var inMethods = false
        for (line in result.lines()) {
            if (line.contains("Methods:")) { inMethods = true; continue }
            if (inMethods) {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("-")) {
                    Regex("""(?:\(m\)\s*)?(\S+)""").find(trimmed)?.let { return it.groupValues[1] }
                }
            }
        }
        return null
    }

    // ==================== HAP ====================

    @Test fun testOpenHap() {
        println("=== open_hap ===")
        val r = callTool("open_hap", "path" to JsonPrimitive(hapFile))
        println(r)
        assertTrue(r.contains("=== HAP File:"))
        assertTrue(r.contains("ABC Files"))
    }

    @Test fun testGetHapManifest() {
        println("=== get_hap_manifest ===")
        val r = callTool("get_hap_manifest", "path" to JsonPrimitive(hapFile))
        println(r)
        assertTrue(r.contains("dev.hackeris.hish"))
    }

    @Test fun testGetObfuscationMap() {
        println("=== get_obfuscation_map ===")
        val r = callTool("get_obfuscation_map", "path" to JsonPrimitive(hapFile))
        println(r)
        assertTrue(r.contains("not found"))
    }

    // ==================== ABC ====================

    @Test fun testOpenAbc() {
        val p = getExtractedAbcPath() ?: return
        println("=== open_abc ===")
        val r = callTool("open_abc", "path" to JsonPrimitive(p))
        println(r)
        assertTrue(r.contains("Opened"))
        assertTrue(r.contains("Classes:"))
    }

    @Test fun testListClasses() {
        val p = getExtractedAbcPath() ?: return
        println("=== list_classes ===")
        val r = callTool("list_classes", "path" to JsonPrimitive(p))
        println(r.take(1500))
        assertTrue(r.contains("Classes in"))
    }

    @Test fun testListClassesFilter() {
        val p = getExtractedAbcPath() ?: return
        println("=== list_classes (filter: BackButton) ===")
        val r = callTool("list_classes", "path" to JsonPrimitive(p), "filter" to JsonPrimitive("BackButton"))
        println(r.take(1000))
        assertTrue(r.contains("BackButton"))
    }

    @Test fun testGetClassDetail() {
        val p = getExtractedAbcPath() ?: return
        val c = getFirstClassName(p) ?: return
        println("=== get_class_detail ($c) ===")
        val r = callTool("get_class_detail", "path" to JsonPrimitive(p), "class_name" to JsonPrimitive(c))
        println(r.take(2000))
        assertTrue(r.contains("Class:"))
    }

    // ==================== 反编译 ====================

    @Test fun testDecompileClass() {
        val p = getExtractedAbcPath() ?: return
        val c = getFirstClassName(p) ?: return
        println("=== decompile_class ($c) ===")
        val r = callTool("decompile_class", "path" to JsonPrimitive(p), "class_name" to JsonPrimitive(c))
        println(r.take(3000))
        assertTrue(r.contains("// Class:") || r.contains("function"))
    }

    @Test fun testDecompileMethod() {
        val p = getExtractedAbcPath() ?: return
        val c = getFirstClassName(p) ?: return
        val m = getFirstMethodName(p, c) ?: return
        println("=== decompile_method ($c.$m) ===")
        val r = callTool("decompile_method", "path" to JsonPrimitive(p), "class_name" to JsonPrimitive(c), "method_name" to JsonPrimitive(m))
        println(r.take(3000))
        assertTrue(r.contains("// Method:") || r.contains("function"))
    }

    @Test fun testDisassembleMethod() {
        val p = getExtractedAbcPath() ?: return
        val c = getFirstClassName(p) ?: return
        val m = getFirstMethodName(p, c) ?: return
        println("=== disassemble_method ($c.$m) ===")
        val r = callTool("disassemble_method", "path" to JsonPrimitive(p), "class_name" to JsonPrimitive(c), "method_name" to JsonPrimitive(m))
        println(r.take(2000))
        assertTrue(r.contains("// Disassembly:") || r.contains("// Instructions:"))
    }

    @Test fun testGetMethodInfo() {
        val p = getExtractedAbcPath() ?: return
        val c = getFirstClassName(p) ?: return
        val m = getFirstMethodName(p, c) ?: return
        println("=== get_method_info ($c.$m) ===")
        val r = callTool("get_method_info", "path" to JsonPrimitive(p), "class_name" to JsonPrimitive(c), "method_name" to JsonPrimitive(m))
        println(r.take(2000))
        assertTrue(r.contains("Method:"))
    }

    // ==================== 搜索 ====================

    @Test fun testSearchStrings() {
        val p = getExtractedAbcPath() ?: return
        println("=== search_strings (http) ===")
        val r = callTool("search_strings", "path" to JsonPrimitive(p), "pattern" to JsonPrimitive("http"))
        println(r.take(2000))
        assertTrue(r.contains("Found") || r.contains("No strings found"))
    }

    @Test fun testSearchInMethod() {
        val p = getExtractedAbcPath() ?: return
        val c = getFirstClassName(p) ?: return
        val m = getFirstMethodName(p, c) ?: return
        println("=== search_in_method ($c.$m) ===")
        val r = callTool("search_in_method", "path" to JsonPrimitive(p), "class_name" to JsonPrimitive(c), "method_name" to JsonPrimitive(m), "pattern" to JsonPrimitive(".*"))
        println(r.take(2000))
        assertFalse(r.startsWith("Error:") && r.contains("Exception"))
    }

    // ==================== XRef ====================

    @Test fun testGetXrefsToMethod() {
        val p = getExtractedAbcPath() ?: return
        val c = getFirstClassName(p) ?: return
        val m = getFirstMethodName(p, c) ?: return
        println("=== get_xrefs_to_method ($c.$m) ===")
        val r = callTool("get_xrefs_to_method", "path" to JsonPrimitive(p), "class_name" to JsonPrimitive(c), "method_name" to JsonPrimitive(m))
        println(r.take(2000))
        assertFalse(r.startsWith("Error:") && r.contains("Exception"))
    }

    @Test fun testGetXrefsToClass() {
        val p = getExtractedAbcPath() ?: return
        val c = getFirstClassName(p) ?: return
        println("=== get_xrefs_to_class ($c) ===")
        val r = callTool("get_xrefs_to_class", "path" to JsonPrimitive(p), "class_name" to JsonPrimitive(c))
        println(r.take(2000))
        assertFalse(r.startsWith("Error:") && r.contains("Exception"))
    }

    @Test fun testGetClassHierarchy() {
        val p = getExtractedAbcPath() ?: return
        val c = getFirstClassName(p) ?: return
        println("=== get_class_hierarchy ($c) ===")
        val r = callTool("get_class_hierarchy", "path" to JsonPrimitive(p), "class_name" to JsonPrimitive(c))
        println(r.take(2000))
        assertFalse(r.startsWith("Error:") && r.contains("Exception"))
    }

    // ==================== 资源 ====================

    @Test fun testSearchResources() {
        println("=== search_resources (app_name) ===")
        val r = callTool("search_resources", "path" to JsonPrimitive(hapFile), "pattern" to JsonPrimitive("app_name"))
        println(r.take(2000))
        assertTrue(r.contains("app_name"))
    }

    @Test fun testResolveResource() {
        println("=== resolve_resource ===")
        val r = callTool("resolve_resource", "path" to JsonPrimitive(hapFile), "reference" to JsonPrimitive("\$string:app_name"))
        println(r.take(2000))
        assertFalse(r.startsWith("Error:") && r.contains("Exception"))
    }
}
