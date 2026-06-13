package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.cfm.argsStr
import me.yricky.oh.abcd.decompiler.ToJs
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.abcd.isa.asmName
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class KazumiAbcTest {

    private val abcFile = File("/Users/vv/project/unitTest/kazumi/ets/modules.abc")

    @Test
    fun testKazumiAbc() {
        if (!abcFile.exists()) {
            println("ABC file not found: ${abcFile.absolutePath}")
            return
        }

        println("Testing: ${abcFile.name}")
        println("Max heap: ${Runtime.getRuntime().maxMemory() / 1024 / 1024} MB")

        val mmap = FileChannel.open(abcFile.toPath())
            .map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))

        var totalMethods = 0
        var successMethods = 0
        var failedMethods = 0
        var skippedMethods = 0
        var errorCount = 0
        val errorSamples = ArrayDeque<String>(30)

        val allClasses = abc.classes.values.filterIsInstance<AbcClass>()
        println("Total classes: ${allClasses.size}")
        val maxMethods = 1000

        for ((classIdx, classItem) in allClasses.withIndex()) {
            if (totalMethods >= maxMethods) break
            if (classIdx % 50 == 0 && classIdx > 0) {
                println("  Progress: class $classIdx/${allClasses.size}, methods so far: $totalMethods")
                System.gc()
            }

            for (method in classItem.methods) {
                if (totalMethods >= maxMethods) break
                totalMethods++
                val code = method.codeItem
                if (code == null) {
                    skippedMethods++
                    continue
                }

                // 临时跳过已知会导致反编译器产生超大字符串的方法
                if (method.name.contains("setSettings")) {
                    skippedMethods++
                    continue
                }

                try {
                    if (totalMethods >= 1180) {
                        println("  Decompiling method $totalMethods: ${classItem.name}.${method.name}")
                    }
                    val asm = Asm(code)
                    StructuredDecompiler.decompile(asm)
                    successMethods++
                } catch (e: ToJs.UnImplementedError) {
                    failedMethods++
                    errorCount++
                    if (errorSamples.size < 30) {
                        errorSamples.add("${classItem.name}.${method.name}: Unimplemented ${e.item.asmName}")
                    }
                } catch (e: NotImplementedError) {
                    failedMethods++
                    errorCount++
                    if (errorSamples.size < 30) {
                        errorSamples.add("${classItem.name}.${method.name}: NotImplementedError")
                    }
                } catch (e: Exception) {
                    failedMethods++
                    errorCount++
                    if (errorSamples.size < 30) {
                        errorSamples.add("${classItem.name}.${method.name}: ${e.javaClass.simpleName}: ${e.message}")
                    }
                } catch (e: OutOfMemoryError) {
                    failedMethods++
                    errorCount++
                    println("OOM at method $totalMethods: ${classItem.name}.${method.name} - ${e.message}")
                    System.gc()
                }
            }
        }

        println("=== Summary ===")
        println("Total methods: $totalMethods")
        println("Success: $successMethods (${if (totalMethods > 0) successMethods * 100.0 / totalMethods else 0}%)")
        println("Failed: $failedMethods")
        println("Skipped: $skippedMethods")

        if (errorSamples.isNotEmpty()) {
            println("\n=== Errors (first ${errorSamples.size} of $errorCount) ===")
            errorSamples.forEach { println("  $it") }
        }
    }

    @Test
    fun dumpSampleMethods() {
        if (!abcFile.exists()) {
            println("ABC file not found")
            return
        }

        val mmap = FileChannel.open(abcFile.toPath())
            .map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))

        var count = 0
        for ((offset, classItem) in abc.classes) {
            if (classItem !is AbcClass) continue

            for (method in classItem.methods) {
                val code = method.codeItem ?: continue
                if (count >= 20) return
                count++

                println("\n// Class: ${classItem.name}")
                println("// Method: ${method.name}")
                println("// Args: ${method.argsStr()}")
                try {
                    val asm = Asm(code)
                    val result = StructuredDecompiler.decompile(asm)
                    println(result)
                } catch (e: Exception) {
                    println("  /* Error: ${e.message} */")
                }
            }
        }
    }

    @Test
    fun findInterestingMethods() {
        if (!abcFile.exists()) {
            println("ABC file not found")
            return
        }

        val mmap = FileChannel.open(abcFile.toPath())
            .map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))

        println("=== Methods with debug param names ===")
        var debugCount = 0
        var restCount = 0

        for ((offset, classItem) in abc.classes) {
            if (classItem !is AbcClass) continue

            for (method in classItem.methods) {
                val code = method.codeItem ?: continue
                val asm = Asm(code)
                val hasRest = asm.irOpList.any { it is me.yricky.oh.abcd.decompiler.behaviour.IrOp.CopyRestArgs }
                val debugParams = (method as? me.yricky.oh.abcd.cfm.AbcMethod)?.debugInfo?.info?.params
                val hasNamedParams = debugParams?.any { it.isNotEmpty() } == true

                if (hasRest) {
                    restCount++
                    if (restCount <= 5) {
                        println("\n[REST $restCount] ${classItem.name}.${method.name}")
                        println("  Args: ${method.argsStr()}")
                        try {
                            val result = StructuredDecompiler.decompile(asm)
                            println("  Decompiled:\n${result.lines().joinToString("\n") { "    $it" }}")
                        } catch (e: Exception) {
                            println("  Error: ${e.message}")
                        }
                    }
                }

                if (hasNamedParams) {
                    debugCount++
                    if (debugCount <= 5) {
                        println("\n[DEBUG $debugCount] ${classItem.name}.${method.name}")
                        println("  Debug params: $debugParams")
                        println("  Args: ${method.argsStr()}")
                    }
                }
            }
        }

        println("\n=== Summary ===")
        println("Methods with rest params: $restCount")
        println("Methods with named debug params: $debugCount")
    }
}
