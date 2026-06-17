package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.ByteOrder

class MelotopiaClassNameTest {
    private val abcFile = File("/home/orz/project/unitTest/hap/Melotopia-1.10.3_HiCar_unsigned_abc/ets/modules.abc")

    @Test
    fun checkClassNames() {
        if (!abcFile.exists()) {
            println("ABC file not found, skipping")
            return
        }
        val mmap = FileChannel.open(abcFile.toPath()).map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))

        // Find all classes containing "Page" or "Login"
        println("=== All class names containing 'Page' or 'Login' ===")
        for ((_, classItem) in abc.classes) {
            if (classItem.name.contains("Page") || classItem.name.contains("Login")) {
                println("  ${classItem.name}")
            }
        }

        // Find func_main_0 methods and their reconstructed classes
        println("\n=== func_main_0 methods with reconstructed classes ===")
        for ((_, classItem) in abc.classes) {
            if (classItem !is AbcClass) continue
            val entryFunc = classItem.methods.find { it.name == "func_main_0" } ?: continue
            val code = entryFunc.codeItem ?: continue
            val asm = Asm(code)
            val reconstructed = StructuredDecompiler.reconstructClasses(asm)
            if (reconstructed.isNotEmpty()) {
                println("\nModule: ${classItem.name}")
                for (clazz in reconstructed) {
                    println("  class ${clazz.className} extends ${clazz.superClassName ?: "<none>"}")
                    println("    fields: ${clazz.fields.size}, methods: ${clazz.allMethods.size}")
                }
            }
        }
    }
}
