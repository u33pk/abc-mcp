package me.yricky.oh.abcd.xref

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.decompiler.structure.decodeMethodName
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.ByteOrder

/**
 * 验证跨模块 xref 修复：用 HISH HAP 测试报告中提到的几个类/方法。
 */
class CrossModuleXRefTest {

    private val abcFile = File("/Users/vv/project/unitTest/hap/yingshilian-HM_abc/ets/modules.abc")

    @Test
    fun testCrossModuleXrefs() {
        if (!abcFile.exists()) {
            println("ABC file not found, skipping")
            return
        }
        val mmap = FileChannel.open(abcFile.toPath()).map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))

        val index = XRefIndex.build(abc)

        // 测试报告中提到的类
        val testClasses = listOf(
            "entry/src/main/ets/components/SimpleWebView",
            "entry/src/main/ets/pages/GuideWebPage",
            "entry/src/main/ets/components/WebTerminal",
        )

        println("=" .repeat(80))
        println("跨模块 xref 验证（HISH HAP）")
        println("=" .repeat(80))

        for (className in testClasses) {
            val fullClassName = abc.classes.values.find { it.name == className }?.name
                ?: abc.classes.values.find { it.name == "&$className&" }?.name?.removePrefix("&")?.removeSuffix("&")
                ?: className

            val instantiations = index.getInstantiations(fullClassName)
            val instanceOfs = index.getInstanceOfs(fullClassName)

            println("\n${"─".repeat(70)}")
            println("Class: $fullClassName")
            println("  Instantiations: ${instantiations.size}")
            instantiations.take(10).forEach { xref ->
                println("    ${xref.callerClass}.${xref.callerDecodedName} (offset 0x${xref.codeOffset.toString(16)})")
            }
            if (instantiations.size > 10) println("    ... and ${instantiations.size - 10} more")

            println("  instanceof checks: ${instanceOfs.size}")
            instanceOfs.take(5).forEach { xref ->
                println("    ${xref.callerClass}.${xref.callerDecodedName} (offset 0x${xref.codeOffset.toString(16)})")
            }
        }

        // 测试报告中提到的方法：startVm.startVm
        println("\n${"─".repeat(70)}")
        println("Method xref: startVm")
        val startVmClass = abc.classes.values.find { it.name.contains("startVm") || it.name.contains("StartVm") }
        if (startVmClass != null && startVmClass is AbcClass) {
            val method = startVmClass.methods.find {
                val decoded = decodeMethodName(it)
                decoded == "startVm" || it.name == "startVm"
            }
            if (method != null) {
                val callers = index.getCallers(method)
                println("  ${startVmClass.name}.${decodeMethodName(method)}")
                println("  Callers: ${callers.size}")
                callers.take(10).forEach { xref ->
                    println("    ${xref.callerClass}.${xref.callerDecodedName} (offset 0x${xref.codeOffset.toString(16)})")
                }
            } else {
                println("  Method 'startVm' not found in ${startVmClass.name}")
                println("  Available methods: ${startVmClass.methods.map { decodeMethodName(it) }.take(20)}")
            }
        } else {
            println("  Class containing 'startVm' not found")
            // 搜索包含 startVm 的类
            val candidates = abc.classes.values.filter { it.name.lowercase().contains("startvm") }
            println("  Candidates: ${candidates.map { it.name }}")
        }

        // 额外：搜索 QemuAgent.exec
        println("\n${"─".repeat(70)}")
        println("Method xref: QemuAgent.exec / execAndGetOutput")
        val qemuClass = abc.classes.values.find { it.name.contains("QemuAgent") || it.name.contains("qemu") }
        if (qemuClass != null && qemuClass is AbcClass) {
            for (method in qemuClass.methods) {
                val decoded = decodeMethodName(method)
                if (decoded == "exec" || decoded == "execAndGetOutput") {
                    val callers = index.getCallers(method)
                    println("  ${qemuClass.name}.$decoded — Callers: ${callers.size}")
                    callers.take(5).forEach { xref ->
                        println("    ${xref.callerClass}.${xref.callerDecodedName} (offset 0x${xref.codeOffset.toString(16)})")
                    }
                }
            }
        } else {
            println("  QemuAgent class not found")
        }

        println("\n${"=".repeat(80)}")
        println("验证完成")
    }
}
