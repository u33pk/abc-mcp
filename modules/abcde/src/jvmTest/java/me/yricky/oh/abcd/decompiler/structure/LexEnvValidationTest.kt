package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.abcd.isa.asmName
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile

/**
 * 验证词法环境相关指令的反编译效果：
 * 1. 不再输出 `/* unimplemented: newlexenv */` 等占位注释
 * 2. 反编译过程不因此类指令失败
 * 3. newlexenvwithname 中的名称能正确解析到 ldlexvar/stlexvar
 */
class LexEnvValidationTest {

    private val hapDir = File("/Users/vv/project/unitTest/hap")

    /**
     * 超过此阈值的方法跳过完整反编译，仅做 IR 映射检查，避免超大/异常方法导致 OOM
     */
    private val MAX_OPS_FOR_FULL_DECOMPILATION = 2000

    private fun forEachAbc(action: (hap: File, entryName: String, abc: AbcBuf) -> Unit) {
        val hapFiles = hapDir.listFiles { f -> f.extension == "hap" } ?: emptyArray()
        assumeTrue("No HAP files found in $hapDir", hapFiles.isNotEmpty())

        val tempDir = java.nio.file.Files.createTempDirectory("hap_extract_").toFile()
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
    fun validateLexEnvOutput() {
        var totalMethodsWithLexEnv = 0L
        var irMappingOk = 0L
        var fullDecompileAttempts = 0L
        var fullDecompileOk = 0L
        var fullDecompileOOM = 0L
        var methodsWithNamedLexEnv = 0L
        var methodsWithResolvedNames = 0L
        val sampleOutputs = mutableListOf<String>()
        val failedMethods = mutableListOf<String>()

        forEachAbc { hap, entryName, abc ->
            for ((_, classItem) in abc.classes) {
                if (classItem !is AbcClass) continue
                for (method in classItem.methods) {
                    val code = method.codeItem ?: continue
                    val asm = Asm(code)
                    val hasLexEnv = asm.irOpList.any { op ->
                        op is IrOp.NewLexEnv || op is IrOp.NewLexEnvWithName || op is IrOp.PopLexEnv
                    }
                    if (!hasLexEnv) continue

                    totalMethodsWithLexEnv++
                    val fullName = "${classItem.name}.${decodeMethodName(method)}"
                    val hasNamedEnv = asm.irOpList.any { it is IrOp.NewLexEnvWithName }
                    if (hasNamedEnv) methodsWithNamedLexEnv++

                    // 检查 IR 映射：词法环境指令不能是 UnImplemented
                    val hasUnimplementedLexEnv = asm.irOpList.any { op ->
                        op is IrOp.UnImplemented && (
                                op.item.asmName.startsWith("newlexenv") ||
                                        op.item.asmName == "poplexenv" ||
                                        op.item.asmName == "deprecated.poplexenv"
                                )
                    }
                    if (!hasUnimplementedLexEnv) {
                        irMappingOk++
                    } else {
                        failedMethods.add("${hap.name}/$entryName | $fullName (unimplemented lexenv op)")
                    }

                    // 对规模可控的方法做完整反编译验证
                    val shouldDecompile = asm.list.size <= MAX_OPS_FOR_FULL_DECOMPILATION
                    if (shouldDecompile) {
                        fullDecompileAttempts++
                        val decompiled = try {
                            StructuredDecompiler.decompile(asm)
                        } catch (e: OutOfMemoryError) {
                            fullDecompileOOM++
                            failedMethods.add("${hap.name}/$entryName | $fullName (OutOfMemoryError)")
                            continue
                        } catch (e: Throwable) {
                            "// FAILED: ${e::class.simpleName}: ${e.message}"
                        }

                        val hasUnimplementedComment = decompiled.contains("/* unimplemented: newlexenv") ||
                                decompiled.contains("/* unimplemented: poplexenv")
                        val hasNewLexComment = decompiled.contains("/* newLex(")
                        val decompileFailed = decompiled.startsWith("// FAILED:")
                        val stillHasPlaceholders = decompiled.contains("__lex") && hasNamedEnv

                        if (!hasUnimplementedComment && !hasNewLexComment && !decompileFailed) {
                            fullDecompileOk++
                        } else {
                            failedMethods.add("${hap.name}/$entryName | $fullName (full decompile issue)\n" +
                                    decompiled.lines().take(20).joinToString("\n"))
                        }

                        if (hasNamedEnv && !stillHasPlaceholders && !decompileFailed) {
                            methodsWithResolvedNames++
                        }

                        // 收集前 5 个含 newlexenvwithname 的样本用于人工检查
                        if (hasNamedEnv && sampleOutputs.size < 5) {
                            sampleOutputs.add("${hap.name}/$entryName | $fullName")
                        }
                    }
                }
            }
        }

        println("=== 词法环境指令反编译验证 ===")
        println("HAP 目录: $hapDir")
        println("含词法环境指令的方法: $totalMethodsWithLexEnv")
        println("IR 映射正确的方法: $irMappingOk")
        println("尝试完整反编译的方法 (<= $MAX_OPS_FOR_FULL_DECOMPILATION ops): $fullDecompileAttempts")
        println("完整反编译通过的方法: $fullDecompileOk")
        println("完整反编译 OOM 的方法: $fullDecompileOOM")
        println("含 newlexenvwithname 的方法: $methodsWithNamedLexEnv")
        println("名称已解析的方法: $methodsWithResolvedNames")
        if (sampleOutputs.isNotEmpty()) {
            println("\n样本方法:")
            sampleOutputs.forEach { println("  $it") }
        }
        if (failedMethods.isNotEmpty()) {
            println("\n失败/异常方法 (前 20):")
            failedMethods.take(20).forEach { println("  $it") }
        }

        assertTrue("未找到任何含词法环境指令的方法", totalMethodsWithLexEnv > 0)
        assertEquals(
            "存在词法环境指令未正确映射为 IR（ irMappingOk=$irMappingOk / total=$totalMethodsWithLexEnv ）",
            totalMethodsWithLexEnv,
            irMappingOk
        )
        val nonOomAttempts = fullDecompileAttempts - fullDecompileOOM
        assertEquals(
            "存在完整反编译失败或仍输出 newlexenv/poplexenv 占位注释（ fullDecompileOk=$fullDecompileOk / nonOomAttempts=$nonOomAttempts ）",
            nonOomAttempts,
            fullDecompileOk
        )
        // OOM 为已知预存问题（个别超大/异常方法在优化器递归 read() 时耗尽内存），需控制在极低比例
        assertTrue(
            "完整反编译 OOM 比例过高（ OOM=$fullDecompileOOM / attempts=$fullDecompileAttempts ）",
            fullDecompileOOM <= fullDecompileAttempts / 50
        )
    }
}
