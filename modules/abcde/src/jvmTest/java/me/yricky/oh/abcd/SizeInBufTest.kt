package me.yricky.oh.abcd

import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class SizeInBufTest {
    private val file = File("/Users/vv/project/unitTest/kazumi/ets/modules.abc")
    private val abc by lazy {
        val mmap = FileChannel.open(file.toPath()).map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        AbcBuf("", wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))
    }

    @Test
    fun testMethod() {
        Assume.assumeTrue("Kazumi ABC not found: $file", file.exists())
        println("file size: ${file.length()}")
        var iSize = 0
        var eSize = 0
        abc.classes.forEach { l ->
            val it = l.value
            if (it is AbcClass) {
                iSize += it.intrinsicSize
                eSize += it.externalSize
            }
        }
        println("total size:${iSize + eSize} \nintrinsicSize:${iSize}\nexternalSize:${eSize}")
    }
}