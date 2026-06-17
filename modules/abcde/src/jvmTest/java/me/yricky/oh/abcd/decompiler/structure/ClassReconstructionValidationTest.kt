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
import java.util.zip.ZipFile

/**
 * 验证 class 重组在真实 HAP 样本上的效果：
 * 1. func_main_0 中的 class 能被识别并解析父类/字段/方法。
 * 2. 同一 func_main_0 内不会出现重复类名。
 * 3. 反编译过程不因此功能崩溃。
 */
class ClassReconstructionValidationTest {

    private val hapDir = File("/Users/vv/project/unitTest/hap")

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
    fun validateClassReconstruction() {
        var totalModules = 0L
        var totalClasses = 0L
        var totalFields = 0L
        var totalMethods = 0L
        var failures = 0L
        val sampleClasses = mutableListOf<String>()

        forEachAbc { hap, entryName, abc ->
            for ((_, classItem) in abc.classes) {
                if (classItem !is AbcClass) continue
                val entry = classItem.methods.firstOrNull { it.name == AbcClass.ENTRY_FUNC_NAME } ?: continue
                totalModules++

                val code = entry.codeItem ?: continue
                val reconstructed = try {
                    StructuredDecompiler.reconstructClasses(Asm(code))
                } catch (e: Throwable) {
                    failures++
                    emptyList()
                }

                val names = reconstructed.map { it.className }
                assertEquals(
                    "Duplicate reconstructed class names in ${hap.name}/$entryName: $names",
                    names.distinct().size,
                    names.size
                )

                totalClasses += reconstructed.size
                totalFields += reconstructed.sumOf { it.fields.size.toLong() }
                totalMethods += reconstructed.sumOf { it.allMethods.size.toLong() }

                if (reconstructed.isNotEmpty() && sampleClasses.size < 10) {
                    sampleClasses.addAll(reconstructed.map {
                        "${hap.name}/$entryName: ${it.className} extends ${it.superClassName ?: "<none>"} fields=${it.fields.size} methods=${it.allMethods.size}"
                    })
                }
            }
        }

        println("Modules with func_main_0: $totalModules")
        println("Reconstructed classes: $totalClasses")
        println("Total fields: $totalFields")
        println("Total methods: $totalMethods")
        println("Failures: $failures")
        println("Samples:")
        sampleClasses.forEach { println("  $it") }

        assertEquals("Duplicate or reconstruction failures detected", 0L, failures)
        assertTrue("Expected at least one reconstructed class", totalClasses > 0)
    }
}
