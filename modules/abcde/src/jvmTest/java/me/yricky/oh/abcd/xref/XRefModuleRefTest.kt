package me.yricky.oh.abcd.xref

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class XRefModuleRefTest {

    private val hapDir = File("/Users/vv/project/unitTest/hap")

    private fun loadAbc(path: String): AbcBuf {
        val file = File(path)
        val mmap = FileChannel.open(file.toPath()).map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        return AbcBuf(file.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))
    }

    private fun forEachAbc(action: (hapName: String, abcPath: String, abc: AbcBuf) -> Unit) {
        val abcDirs = hapDir.listFiles { f -> f.isDirectory && f.name.endsWith("_abc") }
            ?: emptyArray()
        assumeTrue("No _abc dirs found in $hapDir", abcDirs.isNotEmpty())

        abcDirs.sortedBy { it.name }.forEach { dir ->
            dir.walkTopDown().filter { it.extension == "abc" }.sorted().forEach { abcFile ->
                val hapName = dir.name.removeSuffix("_abc")
                action(hapName, abcFile.absolutePath, loadAbc(abcFile.absolutePath))
            }
        }
    }

    @Test
    fun testModuleReferencesNotEmpty() {
        forEachAbc { hapName, abcPath, abc ->
            val index = XRefIndex.build(abc)
            println("[$hapName] ${File(abcPath).name}: moduleRefs=${index.classModuleReferences.size}")
            assertTrue("Module references should not be empty for $hapName/${File(abcPath).name}",
                index.classModuleReferences.isNotEmpty())
        }
    }

    @Test
    fun testArkUIComponentsInModuleRefs() {
        // Module references capture LoadExternalModule / LoadLocalModuleVar usage.
        // Keys are resolved class/import names. Some HAPs may not use ArkUI components
        // directly (e.g., pure logic modules). This test only verifies that the feature
        // works on HAPs with enough module references.
        val arkuiPatterns = listOf("Text", "Image", "Column", "Row", "Button", "List", "ForEach",
            "arkui", "ArkUI", "WebView", "webview", "Navigator", "Swiper", "TabBar", "TextInput",
            "View", "Page", "Component", "Ability", "Provider")

        var anyFound = false
        forEachAbc { hapName, abcPath, abc ->
            val index = XRefIndex.build(abc)
            val allKeys = index.classModuleReferences.keys
            val found = allKeys.filter { key -> arkuiPatterns.any { key.contains(it, ignoreCase = true) } }
            if (found.isNotEmpty()) {
                println("[$hapName] ArkUI-like refs found: ${found.take(10)}")
                anyFound = true
            } else {
                println("[$hapName] No ArkUI-like refs (total keys: ${allKeys.size}, sample: ${allKeys.take(5)})")
            }
        }
        assertTrue("At least one HAP should have ArkUI-like module references", anyFound)
    }

    @Test
    fun testGetModuleReferences() {
        val abcPath = "/Users/vv/project/unitTest/hap/yingshilian-HM_abc/ets/modules.abc"
        assumeTrue("ABC file not found", File(abcPath).exists())

        val abc = loadAbc(abcPath)
        val index = XRefIndex.build(abc)

        // Pick the most-referenced class from module references
        val topEntry = index.classModuleReferences.entries
            .maxByOrNull { it.value.size }
        assertNotNull("Should have at least one module reference", topEntry)

        val className = topEntry!!.key
        val refs = index.getModuleReferences(className)
        println("Top module-referenced class: $className (${refs.size} refs)")
        refs.take(10).forEach { loc ->
            println("  ${loc.callerFullName} (offset 0x${loc.codeOffset.toString(16)})")
        }
        assertTrue("getModuleReferences should return non-empty list", refs.isNotEmpty())
    }

    @Test
    fun testResolveOhmUrlPrefixes() {
        // @normalized:N&&& prefix — embeds class path in URL
        assertEquals(
            "entry/src/main/ets/pages/WebPage",
            ClassNameResolver.resolveOhmUrl("@normalized:N&&&entry/src/main/ets/pages/WebPage&")
        )
        // Other prefixes are module paths, not class names — return null to fallback to localName
        assertNull(ClassNameResolver.resolveOhmUrl("@bundle:entry/src/main/ets/pages/WebPage"))
        assertNull(ClassNameResolver.resolveOhmUrl("@package:lib/foo/bar"))
        assertNull(ClassNameResolver.resolveOhmUrl("@ohos:arkui.web.webview"))
        assertNull(ClassNameResolver.resolveOhmUrl("@unknown:foo"))
        // null input
        assertNull(ClassNameResolver.resolveOhmUrl(null))
    }

    @Test
    fun testNormalizeClassName() {
        assertEquals("entry/src/main/ets/pages/WebPage",
            ClassNameResolver.normalizeClassName("&entry/src/main/ets/pages/WebPage&"))
        assertEquals("SimpleClass",
            ClassNameResolver.normalizeClassName("SimpleClass"))
    }

    private fun assertNotNull(msg: String, value: Any?) {
        org.junit.Assert.assertNotNull(msg, value)
    }

    private fun assertTrue(msg: String, condition: Boolean) {
        org.junit.Assert.assertTrue(msg, condition)
    }

    private fun assertEquals(expected: String?, actual: String?) {
        org.junit.Assert.assertEquals(expected, actual)
    }

    private fun assertNull(value: String?) {
        org.junit.Assert.assertNull(value)
    }
}
