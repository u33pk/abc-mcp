package me.yricky.oh.resde

import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Assume
import org.junit.Test

import org.junit.Assert.*
import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class ResIndexBufTest {

    private fun openResIndex(path: String): ResIndexBuf? {
        val file = File(path)
        if (!file.exists()) return null
        val mmap = FileChannel.open(file.toPath()).map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        return ResIndexBuf(wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))
    }

    @Test
    fun getHeader() {
        val res = openResIndex("/home/orz/project/unitTest/kazumi/test_resources.index")
            ?: return Assume.assumeTrue("test_resources.index not found", false).let { }
        println(String(res.header.version))
        println("size:${res.header.fileSize}")
        println("limitKeyConfigCount:${res.header.limitKeyConfigCount}")
    }

    @Test
    fun testOldFormatResMap() {
        val res = openResIndex("/home/orz/project/unitTest/kazumi/test_resources.index")
            ?: return Assume.assumeTrue("test_resources.index not found", false).let { }
        assertFalse("旧格式 resources.index 不应为空", res.resMap.isEmpty())
        assertTrue("应包含 app_name 资源", res.resMap.values.flatten().any { it.fileName == "app_name" })
        val appName = res.resMap.values.flatten().first { it.fileName == "app_name" }
        assertEquals(ResType.STRING, appName.resType)
        assertEquals("Kazumi", appName.data.asString)
    }

    @Test
    fun testNewFormatResMap() {
        val res = openResIndex("/home/orz/project/unitTest/kazumi/resources.index")
            ?: return Assume.assumeTrue("resources.index not found", false).let { }
        assertFalse("新格式 resources.index 不应为空", res.resMap.isEmpty())
        val allItems = res.resMap.values.flatten()
        assertTrue("应包含 app_name 资源", allItems.any { it.fileName == "app_name" })
        val appName = allItems.first { it.fileName == "app_name" }
        assertEquals(ResType.STRING, appName.resType)
        assertEquals("Kazumi", appName.data.asString)
        println("New format parsed ${allItems.size} items")
    }
}
