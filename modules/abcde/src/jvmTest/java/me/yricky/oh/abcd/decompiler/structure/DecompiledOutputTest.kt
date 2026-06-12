package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class DecompiledOutputTest {
    
    private val testAbcDir = "/home/orz/project/unitTest/out"
    
    @Test
    fun testSimpleFunction() {
        // 测试简单函数的反编译
        val abcFile = File(testAbcDir, "load_string_dynamic.abc")
        if (!abcFile.exists()) {
            println("File not found")
            return
        }
        
        println("Source code (expected):")
        println("""
            function foo() {
                return 'string';
            }
            
            print(foo());
        """.trimIndent())
        
        println("\n${"=".repeat(60)}")
        println("Decompiled output:")
        println("=".repeat(60))
        
        val mmap = FileChannel.open(abcFile.toPath())
            .map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))
        
        for ((offset, classItem) in abc.classes) {
            if (classItem !is AbcClass) continue
            
            for (method in classItem.methods) {
                val code = method.codeItem ?: continue
                
                try {
                    val asm = Asm(code)
                    val result = StructuredDecompiler.decompile(asm)
                    println(result)
                } catch (e: Exception) {
                    println("// Error: ${e.message}")
                }
            }
        }
    }
    
    @Test
    fun testControlFlow() {
        // 测试控制流的反编译
        val abcFile = File(testAbcDir, "create_if_dynamic.abc")
        if (!abcFile.exists()) {
            println("File not found")
            return
        }
        
        println("Testing control flow decompilation...")
        println("=".repeat(60))
        
        val mmap = FileChannel.open(abcFile.toPath())
            .map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))
        
        for ((offset, classItem) in abc.classes) {
            if (classItem !is AbcClass) continue
            
            println("\nClass: ${classItem.name}")
            for (method in classItem.methods) {
                val code = method.codeItem ?: continue
                
                println("\n// Method: ${method.name}")
                try {
                    val asm = Asm(code)
                    val result = StructuredDecompiler.decompile(asm)
                    println(result)
                } catch (e: Exception) {
                    println("// Error: ${e.message}")
                }
            }
        }
    }
    
    @Test
    fun testClassDefinition() {
        // 测试类定义的反编译
        val abcFile = File(testAbcDir, "defineclasswithbuffer_dynamic.abc")
        if (!abcFile.exists()) {
            println("File not found")
            return
        }
        
        println("Testing class definition decompilation...")
        println("=".repeat(60))
        
        val mmap = FileChannel.open(abcFile.toPath())
            .map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))
        
        for ((offset, classItem) in abc.classes) {
            if (classItem !is AbcClass) continue
            
            println("\nClass: ${classItem.name}")
            for (method in classItem.methods) {
                val code = method.codeItem ?: continue
                
                println("\n// Method: ${method.name}")
                try {
                    val asm = Asm(code)
                    val result = StructuredDecompiler.decompile(asm)
                    println(result)
                } catch (e: Exception) {
                    println("// Error: ${e.message}")
                }
            }
        }
    }
    
    @Test
    fun testBinaryOperations() {
        // 测试二元运算的反编译
        val abcFile = File(testAbcDir, "bininst_dynamic.abc")
        if (!abcFile.exists()) {
            println("File not found")
            return
        }
        
        println("Testing binary operations decompilation...")
        println("=".repeat(60))
        
        val mmap = FileChannel.open(abcFile.toPath())
            .map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))
        
        for ((offset, classItem) in abc.classes) {
            if (classItem !is AbcClass) continue
            
            println("\nClass: ${classItem.name}")
            for (method in classItem.methods) {
                val code = method.codeItem ?: continue
                
                println("\n// Method: ${method.name}")
                try {
                    val asm = Asm(code)
                    val result = StructuredDecompiler.decompile(asm)
                    println(result)
                } catch (e: Exception) {
                    println("// Error: ${e.message}")
                }
            }
        }
    }
    
    @Test
    fun testFunctionDefinition() {
        // 测试函数定义的反编译
        val abcFile = File(testAbcDir, "definefunc_dynamic.abc")
        if (!abcFile.exists()) {
            println("File not found")
            return
        }
        
        println("Testing function definition decompilation...")
        println("=".repeat(60))
        
        val mmap = FileChannel.open(abcFile.toPath())
            .map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))
        
        for ((offset, classItem) in abc.classes) {
            if (classItem !is AbcClass) continue
            
            println("\nClass: ${classItem.name}")
            for (method in classItem.methods) {
                val code = method.codeItem ?: continue
                
                println("\n// Method: ${method.name}")
                try {
                    val asm = Asm(code)
                    val result = StructuredDecompiler.decompile(asm)
                    println(result)
                } catch (e: Exception) {
                    println("// Error: ${e.message}")
                }
            }
        }
    }
}
