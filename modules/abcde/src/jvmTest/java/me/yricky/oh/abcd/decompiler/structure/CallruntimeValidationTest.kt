package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.abcd.isa.asmName
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * 验证 callruntime.* 指令实现：
 * 1. 扫描所有 HAP，确认 prefix=0xfb 的 callruntime 指令不再产生 UnImplemented
 * 2. 对含 callruntime 指令的方法执行反编译，确保不崩溃
 */
class CallruntimeValidationTest {

    private val hapDir = File("/home/orz/project/unitTest/hap")

    private fun forEachAbc(action: (hap: File, entryName: String, abc: AbcBuf) -> Unit) {
        val hapFiles = hapDir.listFiles { f -> f.extension == "hap" } ?: emptyArray()
        assertTrue("No HAP files found in $hapDir", hapFiles.isNotEmpty())

        val tempDir = Files.createTempDirectory("hap_callruntime_").toFile()
        tempDir.deleteOnExit()

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
                        action(hapFile, entry.name, abc)
                    }
            }
        }
    }

    @Test
    fun scanCallruntimeUnimplemented() {
        if (!hapDir.exists()) {
            println("HAP directory not found at ${hapDir.absolutePath}, skipping")
            return
        }

        // Map: mnemonic -> count of UnImplemented callruntime instructions
        val unimplCounts = mutableMapOf<String, Int>()
        val unimplExamples = mutableMapOf<String, String>()
        // Total callruntime instructions (implemented + unimplemented)
        var totalCallruntimeOps = 0
        var totalUnimplemented = 0
        var methodsWithCallruntime = 0

        forEachAbc { hap, entryName, abc ->
            for ((_, classItem) in abc.classes) {
                if (classItem !is AbcClass) continue
                for (method in classItem.methods) {
                    val code = method.codeItem ?: continue
                    val asm = Asm(code)
                    var hasCallruntime = false

                    // Check raw ASM items for callruntime prefix (0xfb)
                    for (item in asm.list) {
                        if (item.prefix == 0xfb.toByte()) {
                            totalCallruntimeOps++
                            hasCallruntime = true
                        }
                    }

                    // Check IR ops for unimplemented callruntime
                    for ((idx, op) in asm.irOpList.withIndex()) {
                        if (op is IrOp.UnImplemented) {
                            val item = asm.list[idx]
                            if (item.prefix == 0xfb.toByte()) {
                                val mnemonic = item.asmName
                                totalUnimplemented++
                                unimplCounts[mnemonic] = unimplCounts.getOrDefault(mnemonic, 0) + 1
                                if (!unimplExamples.containsKey(mnemonic)) {
                                    unimplExamples[mnemonic] = "${hap.name}/${classItem.name}.${method.name}"
                                }
                            }
                        }
                    }

                    if (hasCallruntime) methodsWithCallruntime++
                }
            }
        }

        println("\n========== Callruntime Instruction Validation ==========")
        println("Total callruntime instructions found: $totalCallruntimeOps")
        println("Still unimplemented: $totalUnimplemented")
        println("Methods containing callruntime: $methodsWithCallruntime")
        println()

        if (unimplCounts.isNotEmpty()) {
            println("⚠️  Still-unimplemented callruntime instructions:")
            println("%-50s %10s  %s".format("Mnemonic", "Count", "Example"))
            println("-".repeat(90))
            unimplCounts.entries.sortedByDescending { it.value }.forEach { (mnemonic, count) ->
                println("%-50s %10d  %s".format(mnemonic, count, unimplExamples[mnemonic]))
            }
        } else {
            println("✅ All callruntime instructions are now implemented!")
        }

        // Should have zero unimplemented callruntime instructions
        assertEquals(
            "There should be no unimplemented callruntime.* instructions",
            0, totalUnimplemented
        )
    }

    @Test
    fun decompileMethodsWithCallruntime() {
        if (!hapDir.exists()) {
            println("HAP directory not found at ${hapDir.absolutePath}, skipping")
            return
        }

        var methodsScanned = 0
        var methodsDecompiled = 0
        var methodsFailed = 0
        val failures = mutableListOf<String>()

        forEachAbc { hap, entryName, abc ->
            for ((_, classItem) in abc.classes) {
                if (classItem !is AbcClass) continue
                for (method in classItem.methods) {
                    val code = method.codeItem ?: continue
                    val asm = Asm(code)

                    // Check if this method contains any callruntime instruction
                    val hasCallruntime = asm.list.any { it.prefix == 0xfb.toByte() }
                    if (!hasCallruntime) continue

                    methodsScanned++

                    try {
                        val result = StructuredDecompiler.decompile(asm)
                        methodsDecompiled++
                        // Verify no unimplemented callruntime in output
                        if (result.contains("unimplemented: callruntime.")) {
                            failures.add("${classItem.name}.${method.name}: output still contains 'unimplemented: callruntime.'")
                        }
                    } catch (e: Throwable) {
                        methodsFailed++
                        val msg = "${classItem.name}.${method.name}: ${e::class.simpleName}: ${e.message?.take(100)}"
                        failures.add(msg)
                        System.err.println("FAIL: $msg")
                    }
                }
            }
        }

        println("\n========== Callruntime Decompilation Validation ==========")
        println("Methods with callruntime scanned: $methodsScanned")
        println("Successfully decompiled: $methodsDecompiled")
        println("Failed: $methodsFailed")

        if (failures.isNotEmpty()) {
            println("\nFailures:")
            failures.take(20).forEach { println("  - $it") }
            if (failures.size > 20) println("  ... and ${failures.size - 20} more")
        }

        if (methodsFailed > 0) {
            println("\n⚠️  $methodsFailed methods failed to decompile (see details above)")
        } else {
            println("\n✅ All methods with callruntime instructions decompiled successfully!")
        }

        assertTrue("Should have scanned some methods with callruntime", methodsScanned > 0)
    }
}
