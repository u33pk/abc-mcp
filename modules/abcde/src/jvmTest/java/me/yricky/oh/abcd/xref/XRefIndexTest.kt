package me.yricky.oh.abcd.xref

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.cfm.AbcMethod
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class XRefIndexTest {

    private val abcFile = File("/Users/vv/project/unitTest/kazumi/ets/modules.abc")

    @Test
    fun testSetSettingsHasCallers() {
        if (!abcFile.exists()) {
            println("Kazumi ABC file not found, skip")
            return
        }

        val mmap = FileChannel.open(abcFile.toPath())
            .map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))

        val index = XRefIndex.build(abc)

        val targetClass = abc.classes.values.filterIsInstance<AbcClass>()
            .find { it.name.endsWith("/InAppWebView") }
        assertTrue("Should find InAppWebView class", targetClass != null)

        val targetMethod = targetClass!!.methods.find { it.name.contains("setSettings") }
        assertTrue("Should find setSettings method", targetMethod != null)
        assertTrue("setSettings should be AbcMethod", targetMethod is AbcMethod)

        val callers = index.getCallers(targetMethod as AbcMethod)
        println("setSettings callers: ${callers.size}")
        callers.take(10).forEach {
            println("  - ${it.callerFullName} @ 0x${it.codeOffset.toString(16)}")
        }

        assertTrue("setSettings should have at least one caller", callers.isNotEmpty())
    }

    @Test
    fun testIndexIsReusable() {
        if (!abcFile.exists()) {
            println("Kazumi ABC file not found, skip")
            return
        }

        val mmap = FileChannel.open(abcFile.toPath())
            .map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))

        val index1 = XRefIndex.build(abc)
        val index2 = XRefIndex.build(abc)

        val targetClass = abc.classes.values.filterIsInstance<AbcClass>()
            .find { it.name.endsWith("/InAppWebView") }
        val targetMethod = targetClass?.methods?.find { it.name.contains("setSettings") }

        if (targetMethod is AbcMethod) {
            assertTrue("Two builds should produce same caller count",
                index1.getCallers(targetMethod).size == index2.getCallers(targetMethod).size)
        }
    }
}
