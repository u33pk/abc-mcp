package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.cfm.AbcMethod
import me.yricky.oh.abcd.code.Code
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * 迭代器相关 opcode 的 HAP 级验证。
 *
 * 对 /home/orz/project/unitTest/hap 下的 HAP 包扫描，解压其中的 ABC 文件，
 * 快速字节码筛查出包含 `getiterator` / `getpropiterator` / `getnextpropname` 等方法，
 * 抽样执行完整反编译，确保不会崩溃并验证 for-of/for-in 降级效果。
 */
class IteratorOpcodeValidationTest {

    private val hapDir = File("/home/orz/project/unitTest/hap")
    private val sampleSize = 120

    private val iteratorOpBytes = setOf(
        0x36.toByte(), // getnextpropname
        0x66.toByte(), // getpropiterator
        0x67.toByte(), // getiterator
        0x68.toByte(), // closeiterator
        0xab.toByte(), // getiterator (wide imm)
        0xac.toByte()  // closeiterator (wide imm)
    )

    private fun containsIteratorOpcode(code: Code): Boolean {
        val buf = code.instructions
        for (i in 0 until code.codeSize) {
            if (buf.get(i) in iteratorOpBytes) return true
        }
        return false
    }

    @Test
    fun validateIteratorOpcodesInHap() {
        val hapFiles = hapDir.listFiles { f -> f.extension == "hap" } ?: emptyArray()
        assertTrue("No HAP files found in $hapDir", hapFiles.isNotEmpty())

        var methodsTotal = 0L
        var methodsWithIterator = 0L
        val sample = mutableListOf<Triple<File, String, Pair<AbcClass, AbcMethod>>>()

        val tempDir = Files.createTempDirectory("hap_extract_").toFile()
        tempDir.deleteOnExit()

        run sampleLoop@{
            hapFiles.sortedBy { it.name }.forEach { hapFile ->
                ZipFile(hapFile).use { zip ->
                    zip.entries().toList()
                        .filter { !it.isDirectory && it.name.endsWith(".abc") }
                        .forEach { entry ->
                            val target = File(tempDir, "${hapFile.nameWithoutExtension}/${entry.name}")
                            target.parentFile.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                target.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            val bytes = target.readBytes()
                            val abc = AbcBuf(target.name, wrapAsLEByteBuf(ByteBuffer.wrap(bytes)))

                            for ((_, classItem) in abc.classes) {
                                if (classItem !is AbcClass) continue
                                for (method in classItem.methods) {
                                    methodsTotal++
                                    val code = method.codeItem ?: continue
                                    if (!containsIteratorOpcode(code)) continue

                                    methodsWithIterator++
                                    if (sample.size < sampleSize) {
                                        sample.add(Triple(hapFile, entry.name, classItem to method))
                                    }
                                    if (sample.size >= sampleSize) return@sampleLoop
                                }
                            }
                        }
                }
            }
        }

        var loweredForOf = 0L
        var loweredForIn = 0L
        var fallbackWhile = 0L
        var failures = 0L
        val failureExamples = mutableListOf<String>()

        for ((hap, entryName, pair) in sample) {
            val (classItem, method) = pair
            val code = method.codeItem ?: continue
            val asm = Asm(code)
            val result = try {
                StructuredDecompiler.decompileWithInfo(asm)
            } catch (e: Exception) {
                failures++
                if (failureExamples.size < 10) {
                    failureExamples.add("${hap.name}/$entryName | ${classItem.name}.${method.name}: ${e.message}")
                }
                continue
            }

            if (!result.success && result.method == "error") {
                failures++
                if (failureExamples.size < 10) {
                    failureExamples.add("${hap.name}/$entryName | ${classItem.name}.${method.name}: ${result.method}")
                }
                continue
            }

            val codeText = result.code
            when {
                "for (" in codeText -> {
                    if (" of " in codeText) loweredForOf++
                    else if (" in " in codeText) loweredForIn++
                    else fallbackWhile++
                }
                "while (" in codeText -> fallbackWhile++
                else -> fallbackWhile++
            }
        }

        println("=== Iterator Opcode HAP 验证 ===")
        println("HAP 目录: $hapDir")
        println("总方法数: $methodsTotal")
        println("含迭代器 opcode 的方法: $methodsWithIterator")
        println("抽样反编译数: ${sample.size}")
        println("  for-of 降级: $loweredForOf")
        println("  for-in 降级: $loweredForIn")
        println("  while 回退: $fallbackWhile")
        println("  反编译失败: $failures")

        if (failureExamples.isNotEmpty()) {
            println("\n失败示例:")
            failureExamples.forEach { println("  $it") }
        }

        assertTrue("未找到任何含迭代器 opcode 的真实方法", methodsWithIterator > 0)
        assertTrue("有 $failures 个抽样方法反编译失败", failures == 0L)
    }
}
