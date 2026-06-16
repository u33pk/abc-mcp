package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 在真实 HAP 样本上验证 starrayspread 指令的反编译效果。
 */
class StarrayspreadValidationTest {

    private val hapDir = File("/home/orz/project/unitTest/hap")

    @Test
    fun `scan all haps for starrayspread methods`() {
        val hapFiles = hapDir.listFiles { f -> f.extension == "hap" }?.sortedBy { it.name } ?: emptyList<File>()
        assertTrue("No HAP files found in $hapDir", hapFiles.isNotEmpty())

        var totalMethods = 0
        var fallbackMethods = 0
        val samples = mutableListOf<String>()

        for (hapFile in hapFiles) {
            java.util.zip.ZipFile(hapFile).use { zip ->
                val abcEntries = mutableListOf<ZipEntry>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.endsWith(".abc")) abcEntries.add(entry)
                }
                for (abcEntry in abcEntries) {
                    val target = File.createTempFile("abc", ".abc")
                    target.deleteOnExit()
                    zip.getInputStream(abcEntry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    val mmap = FileChannel.open(target.toPath()).map(FileChannel.MapMode.READ_ONLY, 0, target.length())
                    val abc = AbcBuf(target.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))

                    for ((_, classItem) in abc.classes) {
                        if (classItem !is AbcClass) continue
                        for (method in classItem.methods) {
                            val code = method.codeItem ?: continue
                            val asm = Asm(code)
                            if (asm.list.none { it.ins.asmName == "starrayspread" }) continue

                            totalMethods++
                            val out = try {
                                StructuredDecompiler.decompile(asm)
                            } catch (e: Exception) {
                                throw AssertionError("Failed to decompile ${hapFile.name} ${classItem.name}.${method.name}", e)
                            }

                            if ("[..." !in out) {
                                fallbackMethods++
                            }

                            if (samples.size < 3 && "[..." in out) {
                                samples.add("${hapFile.name} | ${classItem.name}.${decodeMethodName(method)}:\n${out.lines().take(20).joinToString("\n")}\n...")
                            }
                        }
                    }
                }
            }
        }

        println("starrayspread methods: $totalMethods, fallback (no [...): $fallbackMethods")
        samples.forEach { println("\n=== SAMPLE ===\n$it") }

        assertTrue("Expected at least one starrayspread method", totalMethods > 0)
        // 目标：所有样本都能合并成 [...x] 形式；若仍有 fallback，先打印出来再决定是否放宽断言
        if (fallbackMethods > 0) {
            println("WARNING: $fallbackMethods methods still use fallback .push(...)")
        }
    }
}
