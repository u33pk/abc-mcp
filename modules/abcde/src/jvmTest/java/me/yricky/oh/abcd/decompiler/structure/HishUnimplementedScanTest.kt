package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.abcd.isa.asmName
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class HishUnimplementedScanTest {

    private val abcFile = File("/home/orz/project/unitTest/HISH/hish-unsigned_abc/ets/modules.abc")

    @Test
    fun scanAllUnimplementedOpcodes() {
        if (!abcFile.exists()) {
            println("ABC file not found at ${abcFile.absolutePath}")
            return
        }

        val mmap = FileChannel.open(abcFile.toPath()).map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))

        // Map: mnemonic -> count
        val counts = mutableMapOf<String, Int>()
        // Map: mnemonic -> Set of method names (first occurrence) to detect func_main_0 vs regular
        val inFuncMain0 = mutableMapOf<String, Boolean>()
        val inRegular = mutableMapOf<String, Boolean>()
        // Keep examples for first occurrence of each mnemonic
        val examples = mutableMapOf<String, String>()

        var totalMethods = 0
        var totalUnimplemented = 0

        for ((_, classItem) in abc.classes) {
            if (classItem !is AbcClass) continue
            for (method in classItem.methods) {
                val code = method.codeItem ?: continue
                totalMethods++
                val asm = Asm(code)
                val isFuncMain0 = method.name == "func_main_0"

                for (op in asm.irOpList) {
                    if (op is IrOp.UnImplemented) {
                        val mnemonic = op.item.asmName
                        totalUnimplemented++
                        counts[mnemonic] = counts.getOrDefault(mnemonic, 0) + 1
                        if (isFuncMain0) {
                            inFuncMain0[mnemonic] = true
                        } else {
                            inRegular[mnemonic] = true
                        }
                        if (!examples.containsKey(mnemonic)) {
                            examples[mnemonic] = "${classItem.name}.${method.name}"
                        }
                    }
                }
            }
        }

        println("\n========== HISH Unimplemented Opcode Scan ==========")
        println("Total methods scanned: $totalMethods")
        println("Total unimplemented occurrences: $totalUnimplemented")
        println("Unique unimplemented opcodes: ${counts.size}")
        println()

        // Sort by frequency descending
        val sorted = counts.entries.sortedByDescending { it.value }

        println("%-20s %10s  %10s  %10s  %s".format(
            "Mnemonic", "Count", "FuncMain0", "Regular", "Example"
        ))
        println("-".repeat(80))

        for ((mnemonic, count) in sorted) {
            val funcMain0Flag = if (inFuncMain0.getOrDefault(mnemonic, false)) "YES" else "NO"
            val regularFlag = if (inRegular.getOrDefault(mnemonic, false)) "YES" else "NO"
            val example = examples[mnemonic] ?: "N/A"
            println("%-20s %10d  %10s  %10s  %s".format(
                mnemonic, count, funcMain0Flag, regularFlag, example
            ))
        }

        println("-".repeat(80))
        println("Summary: Total=$totalUnimplemented, Unique=${counts.size}")
    }
}
