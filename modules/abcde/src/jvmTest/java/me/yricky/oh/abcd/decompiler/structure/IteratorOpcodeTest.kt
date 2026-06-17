package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit

/**
 * 迭代器相关 opcode 的单元测试。
 * 用 es2abc 把 ArkTS/JS 片段编译成 abc，再反编译验证输出。
 */
class IteratorOpcodeTest {

    private val es2abc = File("/Users/vv/project/unitTest/es2abc")

    private fun compileToAbc(source: String): File {
        val srcFile = File.createTempFile("iter_test_src_", ".js")
        srcFile.deleteOnExit()
        srcFile.writeText(source)

        val abcFile = File.createTempFile("iter_test_abc_", ".abc")
        abcFile.deleteOnExit()

        val pb = ProcessBuilder(es2abc.absolutePath, "--output", abcFile.absolutePath, srcFile.absolutePath)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        proc.waitFor(60, TimeUnit.SECONDS)
        val output = proc.inputStream.bufferedReader().readText()
        assertTrue("es2abc failed: $output", proc.exitValue() == 0)
        return abcFile
    }

    private fun decompile(abcFile: File): String {
        val mmap = FileChannel.open(abcFile.toPath())
            .map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))

        val sb = StringBuilder()
        for ((_, classItem) in abc.classes) {
            if (classItem !is AbcClass) continue
            for (method in classItem.methods) {
                val code = method.codeItem ?: continue
                val asm = me.yricky.oh.abcd.isa.Asm(code)
                sb.append("\n// Method: ${method.name}\n")
                try {
                    val out = StructuredDecompiler.decompile(asm)
                    sb.append(out)
                } catch (e: Exception) {
                    sb.append("/* ERROR: ${e.message} */\n")
                    throw AssertionError("Failed to decompile ${method.name}", e)
                }
            }
        }
        return sb.toString()
    }

    @Test
    fun testForOf() {
        val abc = compileToAbc(
            """
            function foo() {
                let arr = [1, 2, 3];
                for (const x of arr) {
                    print(x);
                }
            }
            foo();
            """.trimIndent()
        )
        val out = decompile(abc)
        println("=== for-of decompiled ===")
        println(out)
        assertTrue("Expected for-of syntax", "for (const" in out && " of " in out)
    }

    @Test
    fun testForIn() {
        val abc = compileToAbc(
            """
            function bar(obj) {
                for (const k in obj) {
                    print(k);
                }
            }
            bar({a: 1});
            """.trimIndent()
        )
        val out = decompile(abc)
        println("=== for-in decompiled ===")
        println(out)
        assertTrue("Expected for-in syntax", "for (const" in out && " in " in out)
    }
}
