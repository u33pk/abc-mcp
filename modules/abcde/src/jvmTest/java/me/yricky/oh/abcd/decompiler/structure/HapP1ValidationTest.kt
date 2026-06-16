package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.cfm.AbcMethod
import me.yricky.oh.abcd.cfm.argsStr
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * P1 验证：参数名还原 + copyrestargs
 *
 * 对 /Users/vv/project/unitTest/hap 目录下的所有 HAP 包扫描，
 * 自动解压 ABC 后检查 DebugInfo.params 与 copyrestargs 指令。
 */
class HapP1ValidationTest {

    private val hapDir = File("/home/orz/project/unitTest/hap")

    private fun forEachAbc(action: (hap: File, entryName: String, abc: AbcBuf) -> Unit) {
        val hapFiles = hapDir.listFiles { f -> f.extension == "hap" } ?: emptyArray()
        assertTrue("No HAP files found in $hapDir", hapFiles.isNotEmpty())

        val tempDir = Files.createTempDirectory("hap_extract_").toFile()
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
    fun validateDebugInfoParams() {
        var methodsTotal = 0L
        var methodsWithParams = 0L
        val examples = mutableListOf<String>()

        forEachAbc { hap, entryName, abc ->
            for ((_, classItem) in abc.classes) {
                if (classItem !is AbcClass) continue
                for (method in classItem.methods) {
                    methodsTotal++
                    val params = method.debugInfo?.info?.params
                    if (!params.isNullOrEmpty()) {
                        methodsWithParams++
                        val args = method.argsStr()
                        // 验证 argsStr 里包含了调试信息中的真实参数名，而不是默认 arg0/arg1
                        params.forEachIndexed { _, name ->
                            if (name.isNotEmpty()) {
                                assertTrue(
                                    "参数名 '$name' 未出现在 ${hap.name}/$entryName ${classItem.name}.${method.name} 的 argsStr: $args",
                                    args.contains(name)
                                )
                            }
                        }
                        if (examples.size < 10) {
                            examples.add("${hap.name}/$entryName | ${classItem.name}.${method.name}: $args | params=$params")
                        }
                    }
                }
            }
        }

        println("=== DebugInfo.params 验证 ===")
        println("HAP 目录: $hapDir")
        println("总方法数: $methodsTotal")
        println("有参数名的方法: $methodsWithParams")
        if (examples.isNotEmpty()) {
            println("\n示例:")
            examples.forEach { println("  $it") }
        }
    }

    @Test
    fun validateCopyRestArgs() {
        var methodsWithCopyRest = 0L
        val examples = mutableListOf<String>()

        forEachAbc { hap, entryName, abc ->
            for ((_, classItem) in abc.classes) {
                if (classItem !is AbcClass) continue
                for (method in classItem.methods) {
                    val code = method.codeItem ?: continue
                    val asm = Asm(code)
                    val copyRest = asm.irOpList.mapNotNull { op ->
                        (op as? IrOp.AssignReg)?.right as? IrOp.CopyRestArgs
                    }.firstOrNull()
                    if (copyRest != null) {
                        methodsWithCopyRest++
                        val restIndex = copyRest.startIdx
                        val args = method.argsStr(restIndex)
                        assertTrue(
                            "copyrestargs 方法的签名中应包含 '...': ${hap.name}/$entryName ${classItem.name}.${method.name}: $args",
                            args.contains("...")
                        )
                        if (examples.size < 10) {
                            examples.add("${hap.name}/$entryName | ${classItem.name}.${method.name}: $args")
                        }
                    }
                }
            }
        }

        println("=== copyrestargs 验证 ===")
        println("包含 copyrestargs 的方法: $methodsWithCopyRest")
        if (examples.isNotEmpty()) {
            println("\n示例:")
            examples.forEach { println("  $it") }
        }
    }
}
