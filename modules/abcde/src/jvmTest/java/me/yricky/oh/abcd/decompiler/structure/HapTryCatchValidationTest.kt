package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * 验证 try-catch 边界标注：
 * 1. 能识别含 try-catch 的方法
 * 2. 结构化反编译不崩溃
 * 3. 输出中包含 `// try [` 与 `// catch handler` 注释
 */
class HapTryCatchValidationTest {

    private val hapDir = File("/Users/vv/project/unitTest/hap")

    private fun forEachAbc(action: (hap: File, entryName: String, abc: AbcBuf) -> Unit) {
        val hapFiles = hapDir.listFiles { f -> f.extension == "hap" } ?: emptyArray()
        assumeTrue("No HAP files found in $hapDir", hapFiles.isNotEmpty())

        val tempDir = Files.createTempDirectory("hap_extract_").toFile()
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
    fun validateTryCatchAnnotations() {
        var totalMethods = 0L
        var methodsWithTryCatch = 0L
        var successfulDecompilations = 0L
        val examples = mutableListOf<String>()

        forEachAbc { hap, entryName, abc ->
            for ((_, classItem) in abc.classes) {
                if (classItem !is AbcClass) continue
                for (method in classItem.methods) {
                    totalMethods++
                    val code = method.codeItem ?: continue
                    if (code.tryBlocks.isEmpty()) continue

                    methodsWithTryCatch++
                    val asm = Asm(code)
                    val decompiled = try {
                        StructuredDecompiler.decompile(asm)
                    } catch (e: Exception) {
                        "// Structure analysis failed: ${e.javaClass.simpleName}: ${e.message}\n// Falling back to linear decompilation"
                    }

                    val hasTryAnnotation = decompiled.lineSequence().any {
                        it.trim().startsWith("// try [") || it.trim().startsWith("//   tryCatchSummary:")
                    }
                    val hasCatchAnnotation = decompiled.lineSequence().any {
                        it.trim().startsWith("// catch handler") || it.trim().startsWith("//   tryCatchSummary:")
                    }
                    if (hasTryAnnotation && hasCatchAnnotation) {
                        successfulDecompilations++
                    } else if (examples.size < 10) {
                        examples.add("${hap.name}/$entryName | ${classItem.name}.${method.name}\n$decompiled")
                    }
                }
            }
        }

        println("=== try-catch 边界标注验证 ===")
        println("HAP 目录: $hapDir")
        println("总方法数: $totalMethods")
        println("含 try-catch 的方法: $methodsWithTryCatch")
        println("成功标注的方法: $successfulDecompilations")

        if (examples.isNotEmpty()) {
            println("\n失败示例（前 ${examples.size} 个）:")
            examples.forEachIndexed { index, ex ->
                println("--- example ${index + 1} ---")
                println(ex.lines().take(80).joinToString("\n"))
                println("...")
            }
        }

        assertTrue("未找到任何含 try-catch 的方法", methodsWithTryCatch > 0)
        assertTrue(
            "存在 try-catch 方法未能生成 try/catch 标注（ successful=$successfulDecompilations / total=$methodsWithTryCatch ）",
            successfulDecompilations == methodsWithTryCatch
        )
    }
}
