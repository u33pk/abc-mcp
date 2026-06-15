package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.cfm.AbcMethod
import me.yricky.oh.abcd.cfm.argsStr
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class HishAbcScanTest {

    private val abcFile = File("/home/orz/project/unitTest/HISH/hish-unsigned_abc/ets/modules.abc")

    @Test
    fun scanDebugInfoParams() {
        if (!abcFile.exists()) { println("ABC not found"); return }
        val mmap = FileChannel.open(abcFile.toPath()).map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))

        var methodsWithParams = 0
        var methodsTotal = 0
        val examples = mutableListOf<String>()

        for ((_, classItem) in abc.classes) {
            if (classItem !is AbcClass) continue
            for (method in classItem.methods) {
                methodsTotal++
                val abcMethod = method as? AbcMethod ?: continue
                val params = abcMethod.debugInfo?.info?.params
                if (!params.isNullOrEmpty()) {
                    methodsWithParams++
                    if (examples.size < 20) {
                        examples.add("${classItem.name}.${method.name}: args=${method.argsStr()}, params=$params")
                    }
                }
            }
        }

        println("=== HISH DebugInfo.params 扫描 ===")
        println("总方法数: $methodsTotal")
        println("有参数名的方法: $methodsWithParams")
        if (examples.isNotEmpty()) {
            println("\n示例:")
            examples.forEach { println("  $it") }
        }
    }

    @Test
    fun scanCopyRestArgs() {
        if (!abcFile.exists()) { println("ABC not found"); return }
        val mmap = FileChannel.open(abcFile.toPath()).map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))

        var methodsWithCopyRest = 0
        val examples = mutableListOf<String>()

        for ((_, classItem) in abc.classes) {
            if (classItem !is AbcClass) continue
            for (method in classItem.methods) {
                val code = method.codeItem ?: continue
                val asm = Asm(code)
                val hasCopyRest = asm.irOpList.any { op ->
                    op is IrOp.AssignReg && op.right is IrOp.CopyRestArgs
                }
                if (hasCopyRest) {
                    methodsWithCopyRest++
                    if (examples.size < 20) {
                        examples.add("${classItem.name}.${method.name}")
                    }
                }
            }
        }

        println("=== HISH copyrestargs 扫描 ===")
        println("包含 copyrestargs 的方法: $methodsWithCopyRest")
        if (examples.isNotEmpty()) {
            println("\n示例:")
            examples.forEach { println("  $it") }
        }
    }

    @Test
    fun scanArgStr() {
        if (!abcFile.exists()) { println("ABC not found"); return }
        val mmap = FileChannel.open(abcFile.toPath()).map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))

        // 找几个有参数的方法，看 argsStr 输出
        var count = 0
        for ((_, classItem) in abc.classes) {
            if (classItem !is AbcClass) continue
            for (method in classItem.methods) {
                val code = method.codeItem ?: continue
                if (code.numArgs > 3) {
                    val args = (method as? AbcMethod)?.argsStr() ?: "N/A"
                    println("${classItem.name}.${method.name}(${code.numArgs} args): $args")
                    count++
                    if (count >= 30) return
                }
            }
        }
    }
}
