package me.yricky.oh.abcd.decompiler

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.isa.asmName
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.OutputStreamWriter
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class CodeSegmentTest {

    private fun openAbc(): AbcBuf? {
        val path = System.getenv("abcPath") ?: "/Users/vv/project/unitTest/kazumi/ets/modules.abc"
        val file = File(path)
        if (!file.exists()) return null
        val mmap = FileChannel.open(file.toPath()).map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        return AbcBuf("", wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))
    }

    @Test
    fun testGenLinear() {
        val abc = openAbc()
        Assume.assumeNotNull(abc)
        abc!!
        var totalCount = 0
        abc.classes.asSequence().mapNotNull { it.value as? AbcClass }
            .mapNotNull { it.methods.find { it.name == "func_main_0" } }
            .mapNotNull { it.codeItem }
            .forEach {
                totalCount++
                if (it.tryBlocks.isEmpty()) {
                    CodeSegment.genLinear(it.asm)
                }
            }
        println("genLinear total: $totalCount")
    }

    @Test
    fun test() {
        val abc = openAbc()
        Assume.assumeNotNull(abc)
        abc!!
        var totalCount = 0
        var passCount = 0
        var uIByteCodeCount = 0
        var othUnImplCount = 0
        var assertFailedCount = 0
        var lErrCount = 0

        File("/tmp/pass.txt").let {
            println(it.absolutePath)
            if (it.exists()) {
                it.renameTo(File("/tmp/pass.txt.old"))
            }
        }
        val passFile = File("/tmp/pass.txt")
        passFile.createNewFile()
        val fos = OutputStreamWriter(passFile.outputStream())
        val unIMap = mutableMapOf<String, Int>()

        abc.classes.asSequence().mapNotNull { it.value as? AbcClass }
            .flatMap { it.methods }
            .mapNotNull { it.codeItem }
            .forEach {
                totalCount++
                try {
                    ToJs(it.asm).toJS(true)
                    fos.append(it.method.name).append('\n')
                    passCount++
                } catch (e: ToJs.UnImplementedError) {
                    unIMap[e.item.asmName] = (unIMap[e.item.asmName] ?: 0) + 1
                    uIByteCodeCount++
                } catch (_: NotImplementedError) {
                    othUnImplCount++
                } catch (_: AssertionError) {
                    assertFailedCount++
                } catch (_: IllegalStateException) {
                    lErrCount++
                }
            }
        fos.close()
        println("total:${totalCount},pass:${passCount}(${passCount * 100.0 / totalCount}%)\nUnImplementedByteCodeCount:${uIByteCodeCount},othUnImplCount:${othUnImplCount},assertFailedCount:${assertFailedCount},lErrCount:${lErrCount}")
        println("unImpl bytecodes:")
        unIMap.asSequence().sortedBy { -it.value }.forEach {
            println("${it.key}:${it.value}")
        }
    }
}
