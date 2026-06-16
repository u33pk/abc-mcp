package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.cfm.AbcMethod
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
 * 验证 throw.ifsupernotcorrectcall 指令的反编译效果：
 * 1. 不再输出 `/* unimplemented: throw.ifsupernotcorrectcall */` 占位注释
 * 2. 反编译过程不因此指令失败
 */
class SuperCallCheckValidationTest {

    private val hapDir = File("/home/orz/project/unitTest/hap")

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
    fun validateSuperCallCheckOutput() {
        var totalMethodsWithCheck = 0L
        var successfulMethods = 0L
        val constructorSamples = mutableListOf<String>()

        forEachAbc { hap, entryName, abc ->
            for ((_, classItem) in abc.classes) {
                if (classItem !is AbcClass) continue
                for (method in classItem.methods) {
                    val code = method.codeItem ?: continue
                    val asm = Asm(code)
                    // 通过原始指令名检测是否包含 throw.ifsupernotcorrectcall
                    //（该指令已映射为 NOP，不能通过 IrOp.UnImplemented 检测）
                    val hasCheck = asm.list.any { item ->
                        item.asmName == "throw.ifsupernotcorrectcall"
                    }
                    if (!hasCheck) continue

                    totalMethodsWithCheck++
                    val fullName = "${classItem.name}.${decodeMethodName(method)}"
                    val decompiled = try {
                        StructuredDecompiler.decompile(asm)
                    } catch (e: Exception) {
                        "// FAILED: ${e.message}"
                    }

                    val hasUnimplementedComment = decompiled.contains("/* unimplemented: throw.ifsupernotcorrectcall */")
                    val decompileFailed = decompiled.startsWith("// FAILED:")

                    if (!hasUnimplementedComment && !decompileFailed) {
                        successfulMethods++
                    }

                    // 收集前 5 个样本用于人工检查
                    if (constructorSamples.size < 5) {
                        constructorSamples.add("${hap.name}/$entryName | $fullName")
                    }

                    // 对未通过的方法输出诊断信息
                    if (hasUnimplementedComment || decompileFailed) {
                        println("--- FAILED: ${hap.name}/$entryName | $fullName ---")
                        println(decompiled.lines().take(30).joinToString("\n"))
                        println("...")
                    }
                }
            }
        }

        println("=== throw.ifsupernotcorrectcall 反编译验证 ===")
        println("HAP 目录: $hapDir")
        println("含 throw.ifsupernotcorrectcall 的方法: $totalMethodsWithCheck")
        println("无占位注释的方法: $successfulMethods")
        if (constructorSamples.isNotEmpty()) {
            println("\n样本方法:")
            constructorSamples.forEach { println("  $it") }
        }

        assertTrue("未找到任何含 throw.ifsupernotcorrectcall 的方法", totalMethodsWithCheck > 0)
        assertEquals(
            "存在 throw.ifsupernotcorrectcall 方法仍输出 unimplemented 注释（ successful=$successfulMethods / total=$totalMethodsWithCheck ）",
            totalMethodsWithCheck,
            successfulMethods
        )
        assertTrue("未找到任何含 throw.ifsupernotcorrectcall 的样本方法", constructorSamples.isNotEmpty())
    }
}
