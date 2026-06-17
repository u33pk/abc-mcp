package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * 输出各类 callruntime 新指令的反编译样例。
 * 逐个 HAP 扫描，为每个 opcode 收集独立样本。
 */
class CallruntimeOutputDemoTest {

    private val hapDir = File("/home/orz/project/unitTest/hap")

    private val targetOpcodes = mapOf(
        0x00 to "notifyconcurrentresult",
        0x03 to "topropertykey",
        0x04 to "createprivateproperty",
        0x05 to "defineprivateproperty",
        0x06 to "callinit",
        0x07 to "definesendableclass",
        0x08 to "ldsendableclass",
        0x0a to "wideldsendableexternalmodulevar",
        0x0b to "newsendableenv",
        0x0d to "stsendablevar_4bit",
        0x0e to "stsendablevar_8bit",
        0x10 to "ldsendablevar_4bit",
        0x11 to "ldsendablevar_8bit",
        0x12 to "wideldsendablevar",
        0x15 to "ldlazymodulevar",
        0x17 to "ldlazysendablemodulevar",
        0x19 to "supercallforwardallargs",
        0x1a to "ldsendablelocalmodulevar",
    )

    @Test
    fun outputSamplesPerOpcode() {
        if (!hapDir.exists()) {
            println("HAP directory not found, skipping")
            return
        }

        // Map: opcode → (methodName, lines, hapName)
        val samples = mutableMapOf<Int, Triple<String, List<String>, String>>()
        val tempDir = Files.createTempDirectory("callruntime_demo_").toFile()
        tempDir.deleteOnExit()

        // Phase 1: Scan all HAPs for ALL methods, collect one sample per opcode
        hapDir.listFiles { f -> f.extension == "hap" }?.sortedBy { it.name }?.forEach outer@{ hapFile ->
            if (samples.size >= targetOpcodes.size) return@outer
            ZipFile(hapFile).use { zip ->
                zip.entries().toList()
                    .filter { !it.isDirectory && it.name.endsWith(".abc") }
                    .forEach { entry ->
                        if (samples.size >= targetOpcodes.size) return@forEach
                        val target = File(tempDir, "${hapFile.nameWithoutExtension}/${entry.name}")
                        target.parentFile.mkdirs()
                        zip.getInputStream(entry).use { it.copyTo(target.outputStream()) }
                        val abc = AbcBuf(target.name, wrapAsLEByteBuf(ByteBuffer.wrap(target.readBytes())))

                        for ((_, classItem) in abc.classes) {
                            if (classItem !is AbcClass) continue
                            if (samples.size >= targetOpcodes.size) break
                            for (method in classItem.methods) {
                                if (samples.size >= targetOpcodes.size) break
                                val code = method.codeItem ?: continue
                                val asm = Asm(code)

                                // Check which unseen opcodes this method has
                                val unseenOpcodes = asm.list
                                    .filter { it.prefix == 0xfb.toByte() }
                                    .map { (it.opUnits[1] as Byte).toInt() and 0xFF }
                                    .filter { it in targetOpcodes && it !in samples }
                                    .toSet()
                                if (unseenOpcodes.isEmpty()) continue

                                try {
                                    val result = StructuredDecompiler.decompile(asm)
                                    val lines = result.lines()
                                    for (op in unseenOpcodes) {
                                        if (op !in samples) {
                                            samples[op] = Triple(
                                                "${classItem.name}.${method.name}",
                                                lines,
                                                hapFile.name
                                            )
                                        }
                                    }
                                } catch (_: Throwable) { }
                            }
                        }
                    }
            }
        }

        // Print results
        println("=".repeat(80))
        println("callruntime.* 新指令反编译样例（${samples.size} / ${targetOpcodes.size} 类）")
        println("=".repeat(80))

        for ((opcode, data) in samples.entries.sortedBy { it.key }) {
            val (methodName, code, hapName) = data
            val opName = targetOpcodes[opcode] ?: "unknown"

            println()
            println("┌${"─".repeat(78)}")
            println("│ callruntime.$opName  (opcode=0x${opcode.toString(16)})")
            println("│ HAP: $hapName")
            println("│ Method: $methodName")
            println("│ Total lines: ${code.size}")
            println("├${"─".repeat(78)}")
            val meaningful = code.dropWhile { it.isBlank() }
            for (line in meaningful.take(40)) {
                println("│ $line")
            }
            if (meaningful.size > 40) {
                println("│ ... (${meaningful.size - 40} more lines)")
            }
            println("└${"─".repeat(78)}")
        }

        val missing = targetOpcodes.keys - samples.keys
        if (missing.isNotEmpty()) {
            println("\n⚠️  未找到样本的指令（在测试 HAP 中频率极低或仅在特定上下文出现）：")
            missing.sorted().forEach { println("  - callruntime.${targetOpcodes[it]} (0x${it.toString(16)})") }
        }

        println("\n✅ 共展示 ${samples.size} / ${targetOpcodes.size} 类新指令的反编译样例")
    }
}
