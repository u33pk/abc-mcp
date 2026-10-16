package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.yricky.oh.mcp.session.SessionManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * close_abc / list_sessions 工具测试。
 * 手工构造 60 字节最小 PANDA ABC 头（0 类），无需外部样本文件。
 */
class SessionToolsTest {

    private val sessionManager = SessionManager()
    private val registry = ToolRegistry(sessionManager)

    private fun callTool(name: String, vararg pairs: Pair<String, JsonElement>): String {
        val args = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
        return registry.callTool(name, args)
    }

    private fun ByteArray.putLeInt(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
        this[offset + 2] = (value shr 16).toByte()
        this[offset + 3] = (value shr 24).toByte()
    }

    private fun writeMinimalAbc(file: File) {
        val buf = ByteArray(60)
        val magic = byteArrayOf(
            'P'.code.toByte(), 'A'.code.toByte(), 'N'.code.toByte(),
            'D'.code.toByte(), 'A'.code.toByte(), 0, 0, 0
        )
        magic.copyInto(buf, destinationOffset = 0)
        buf.putLeInt(16, 60) // fileSize
        file.writeBytes(buf)
    }

    @Test
    fun testListSessionsEmpty() {
        val result = callTool("list_sessions")
        println(result)
        assertTrue("空会话应提示无打开文件", result.contains("No open sessions"))
    }

    @Test
    fun testCloseNotOpened() {
        val result = callTool("close_abc", "path" to JsonPrimitive("/tmp/never_opened.abc"))
        println(result)
        assertTrue("未打开的文件应返回错误提示", result.startsWith("Error:"))
    }

    @Test
    fun testOpenListCloseLifecycle() {
        val abcFile = File.createTempFile("session_tool_test_", ".abc")
        abcFile.deleteOnExit()
        writeMinimalAbc(abcFile)
        val path = abcFile.absolutePath

        // 打开后 list_sessions 应包含该路径与类数
        val openResult = callTool("open_abc", "path" to JsonPrimitive(path))
        assertTrue(openResult.contains("Opened:"))

        val listResult = callTool("list_sessions")
        println(listResult)
        assertTrue(listResult.contains(path))
        assertTrue(listResult.contains("classes=0"))

        // 构建 xref 索引后会话条目应体现索引标记
        sessionManager.getXRefIndex(path)
        val listWithXref = callTool("list_sessions")
        println(listWithXref)
        assertTrue(listWithXref.contains("xref=true"))

        // 关闭后会话清空
        val closeResult = callTool("close_abc", "path" to JsonPrimitive(path))
        println(closeResult)
        assertTrue(closeResult.contains("Closed"))

        val listAfterClose = callTool("list_sessions")
        assertTrue(listAfterClose.contains("No open sessions"))

        // 关闭后同路径可重新打开
        val reopenResult = callTool("open_abc", "path" to JsonPrimitive(path))
        assertTrue(reopenResult.contains("Opened:"))
    }

    @Test
    fun testCloseAlsoDropsXrefIndex() {
        val abcFile = File.createTempFile("session_tool_test_", ".abc")
        abcFile.deleteOnExit()
        writeMinimalAbc(abcFile)
        val path = abcFile.absolutePath

        sessionManager.open(path)
        sessionManager.getXRefIndex(path)
        assertTrue("close 应返回 true（存在已打开的会话）", sessionManager.close(path))

        val listAfterClose = callTool("list_sessions")
        assertFalse("close 后列表不应再包含该路径", listAfterClose.contains(path))
    }
}
