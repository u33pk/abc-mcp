package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.abcd.isa.asmName
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * 抽样验证含 callruntime 指令的方法反编译输出。
 * 从 Melotopia HAP 中选取 3 个含不同 callruntime 指令的方法，打印反编译结果。
 */
class CallruntimeSpotCheckTest {

    @Test
    fun spotCheckDecompiledOutput() {
        val hapDir = File("/home/orz/project/unitTest/hap")
        if (!hapDir.exists()) {
            println("HAP directory not found, skipping")
            return
        }

        val tempDir = Files.createTempDirectory("callruntime_spot_").toFile()
        tempDir.deleteOnExit()

        val hapFile = hapDir.listFiles { f -> f.extension == "hap" }!!
            .firstOrNull { it.name.contains("Melotopia") } ?: run {
            println("Melotopia HAP not found, skipping")
            return
        }
        println("Using HAP: ${hapFile.name}")

        var shown = 0
        val seenOpSets = mutableSetOf<String>()

        ZipFile(hapFile).use { zip ->
            zip.entries().toList()
                .filter { !it.isDirectory && it.name.endsWith(".abc") }
                .forEach { entry ->
                    if (shown >= 5) return
                    val target = File(tempDir, entry.name)
                    target.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    val abc = AbcBuf(target.name, wrapAsLEByteBuf(ByteBuffer.wrap(target.readBytes())))

                    for ((_, classItem) in abc.classes) {
                        if (classItem !is AbcClass) continue
                        for (method in classItem.methods) {
                            if (shown >= 5) break
                            val code = method.codeItem ?: continue
                            val asm = Asm(code)

                            val callruntimeOps = asm.list
                                .filter { it.prefix == 0xfb.toByte() }
                                .map { it.asmName }
                                .distinct()
                                .sorted()

                            if (callruntimeOps.isEmpty()) continue

                            // Deduplicate by op set to show variety
                            val opKey = callruntimeOps.joinToString(",")
                            if (seenOpSets.contains(opKey)) continue
                            seenOpSets.add(opKey)

                            try {
                                val result = StructuredDecompiler.decompile(asm)
                                println("\n${"=".repeat(70)}")
                                println("Method: ${classItem.name}.${method.name}")
                                println("Callruntime ops: $callruntimeOps")
                                println("-".repeat(70))
                                result.lines().take(40).forEach { println(it) }
                                if (result.lines().size > 40) println("  ... (${result.lines().size} lines total)")
                                shown++
                            } catch (e: Throwable) {
                                println("\nFAIL: ${classItem.name}.${method.name}: ${e::class.simpleName}: ${e.message?.take(200)}")
                            }
                        }
                    }
                }
        }

        println("\n${"=".repeat(70)}")
        println("Spot check complete: showed $shown methods with different callruntime patterns")
    }
}
