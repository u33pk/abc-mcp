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
 * 验证 async/await 相关指令的反编译效果：
 * 1. 不再输出 `/* unimplemented: async... */` 等占位注释
 * 2. 函数头带有 `async function`
 * 3. 含 await 指令的方法输出中出现 `await` 关键字
 * 4. 报告中提到的目标方法能成功反编译
 */
class AsyncAwaitValidationTest {

    private val hapDir = File("/Users/vv/project/unitTest/hap")

    private fun forEachAbc(action: (hap: File, entryName: String, abc: AbcBuf) -> Unit) {
        val hapFiles = hapDir.listFiles { f -> f.extension == "hap" } ?: emptyArray()
        assumeTrue("No HAP files found in $hapDir", hapFiles.isNotEmpty())

        val tempDir = java.nio.file.Files.createTempDirectory("hap_extract_").toFile()
        tempDir.deleteOnExit()

        hapFiles
            .filter { it.name.contains("Melotopia", ignoreCase = true) }
            .sortedBy { it.name }
            .forEach { hapFile ->
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
    fun validateAsyncAwaitOutput() {
        var totalAsyncMethods = 0L
        var methodsWithAwait = 0L
        var successfulAsyncMethods = 0L
        val targetResults = mutableMapOf<String, String>()
        val targetPatterns = listOf(
            "EntryAbility.onCreate",
            "checkApi.checkValidUrl",
            "play.play_dav",
            "play.play_local",
            "play.play_online",
            "RequestUtil.commonRequest",
            "AppUtils.appInit",
            "AppUtils.changeSettings",
            "AppUtils.checkUserLogin"
        )

        forEachAbc { hap, entryName, abc ->
            for ((_, classItem) in abc.classes) {
                if (classItem !is AbcClass) continue
                for (method in classItem.methods) {
                    val code = method.codeItem ?: continue
                    val asm = Asm(code)
                    val hasAsyncEnter = asm.irOpList.any { it is IrOp.AsyncFunctionEnter }
                    if (!hasAsyncEnter) continue

                    totalAsyncMethods++
                    val hasAwaitInsn = asm.irOpList.any { it is IrOp.Await }
                    if (hasAwaitInsn) methodsWithAwait++

                    val fullName = "${classItem.name}.${decodeMethodName(method)}"
                    val decompiled = try {
                        StructuredDecompiler.decompile(asm)
                    } catch (e: Exception) {
                        "// FAILED: ${e.message}"
                    }

                    val hasAsyncKeyword = decompiled.contains("async function")
                    val hasUnimplementedAsync = decompiled.contains("/* unimplemented: asyncfunction") ||
                            decompiled.contains("/* unimplemented: suspendgenerator") ||
                            decompiled.contains("/* unimplemented: resumegenerator") ||
                            decompiled.contains("/* unimplemented: getresumemode")
                    val hasAwaitKeyword = !hasAwaitInsn || decompiled.contains("await")

                    if (hasAsyncKeyword && !hasUnimplementedAsync && hasAwaitKeyword) {
                        successfulAsyncMethods++
                    }

                    targetPatterns.firstOrNull { pattern ->
                        pattern.split(".").all { fullName.contains(it) }
                    }?.let {
                        targetResults[it] = decompiled
                    }
                }
            }
        }

        println("=== async/await 反编译验证 ===")
        println("HAP 目录: $hapDir")
        println("async 方法总数: $totalAsyncMethods")
        println("含 await 指令的方法: $methodsWithAwait")
        println("成功生成 async/await 标注的方法: $successfulAsyncMethods")

        if (targetResults.isNotEmpty()) {
            println("\n目标方法反编译结果（${targetResults.size}/${targetPatterns.size} 命中）:")
            targetResults.forEach { (name, code) ->
                println("--- $name ---")
                println(code.lines().take(40).joinToString("\n"))
                println("...")
            }
        }

        assertTrue("未找到任何 async 方法", totalAsyncMethods > 0)
        assertEquals(
            "存在 async 方法未能生成 async/await 标注（ successful=$successfulAsyncMethods / total=$totalAsyncMethods ）",
            totalAsyncMethods,
            successfulAsyncMethods
        )
    }
}
