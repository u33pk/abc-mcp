package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.cfm.AbcMethod
import me.yricky.oh.abcd.decompiler.CodeSegment
import me.yricky.oh.abcd.decompiler.ToJs
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.abcd.isa.asmName
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class StructuredDecompilerTest {
    
    private val testAbcDir = "/Users/vv/project/unitTest/out"
    
    @Test
    fun testAllAbcFiles() {
        val dir = File(testAbcDir)
        if (!dir.exists()) {
            println("Test directory not found: $testAbcDir")
            return
        }
        
        val abcFiles = dir.listFiles { file -> file.extension == "abc" }?.sorted() ?: emptyList()
        println("Found ${abcFiles.size} ABC files in $testAbcDir")
        
        var totalMethods = 0
        var successMethods = 0
        var failedMethods = 0
        var skippedMethods = 0
        
        val errors = mutableListOf<String>()
        
        for (abcFile in abcFiles) {
            println("\n=== Testing: ${abcFile.name} ===")
            try {
                val mmap = FileChannel.open(abcFile.toPath())
                    .map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
                val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))
                
                for ((offset, classItem) in abc.classes) {
                    if (classItem !is AbcClass) continue
                    
                    for (method in classItem.methods) {
                        totalMethods++
                        val code = method.codeItem
                        if (code == null) {
                            skippedMethods++
                            continue
                        }
                        
                        try {
                            val asm = Asm(code)
                            val result = StructuredDecompiler.decompile(asm)
                            successMethods++
                            
                            // 打印成功的方法
                            if (result.isNotEmpty() && !result.startsWith("// Structure analysis failed")) {
                                println("  ✓ ${classItem.name}.${method.name}")
                            }
                        } catch (e: ToJs.UnImplementedError) {
                            failedMethods++
                            val errorMsg = "${abcFile.name}/${classItem.name}.${method.name}: Unimplemented bytecode ${e.item.asmName}"
                            errors.add(errorMsg)
                            println("  ⚠ ${classItem.name}.${method.name} (unimplemented: ${e.item.asmName})")
                        } catch (e: NotImplementedError) {
                            failedMethods++
                            val errorMsg = "${abcFile.name}/${classItem.name}.${method.name}: NotImplementedError"
                            errors.add(errorMsg)
                            println("  ⚠ ${classItem.name}.${method.name} (not implemented)")
                        } catch (e: Exception) {
                            failedMethods++
                            val errorMsg = "${abcFile.name}/${classItem.name}.${method.name}: ${e.message}"
                            errors.add(errorMsg)
                            
                            // 降级到旧的反编译器
                            try {
                                val asm = Asm(code)
                                val fallback = ToJs(asm).toJS()
                                println("  ⚠ ${classItem.name}.${method.name} (fallback)")
                            } catch (e2: Exception) {
                                println("  ✗ ${classItem.name}.${method.name}: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("  Error loading ${abcFile.name}: ${e.message}")
            }
        }
        
        println("\n=== Summary ===")
        println("Total methods: $totalMethods")
        println("Success: $successMethods (${successMethods * 100.0 / totalMethods}%)")
        println("Failed: $failedMethods")
        println("Skipped: $skippedMethods")
        
        if (errors.isNotEmpty()) {
            println("\n=== Errors ===")
            errors.take(20).forEach { println("  $it") }
            if (errors.size > 20) {
                println("  ... and ${errors.size - 20} more")
            }
        }
    }

    @Test
    fun testComplexLoop() {
        val abcFile = File(testAbcDir, "complex_loop.abc")
        if (!abcFile.exists()) {
            println("File not found: ${abcFile.absolutePath}")
            return
        }

        val sourceFile = File("/home/orz/project/unitTest/isa/isa_dynamic/complex_loop/complex_loop.js")
        if (sourceFile.exists()) {
            println("=== Source Code ===")
            println(sourceFile.readText())
        }

        println("\n${"=".repeat(60)}")
        println("Decompiled Output:")
        println("=".repeat(60))

        val mmap = FileChannel.open(abcFile.toPath())
            .map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))

        for ((offset, classItem) in abc.classes) {
            if (classItem !is AbcClass) continue

            for (method in classItem.methods) {
                val code = method.codeItem ?: continue

                println("\n// Method: ${method.name}")
                try {
                    val asm = Asm(code)
                    val result = StructuredDecompiler.decompile(asm)
                    println(result)
                } catch (e: ToJs.UnImplementedError) {
                    println("  /* unimplemented: ${e.item.asmName} */")
                } catch (e: NotImplementedError) {
                    println("  /* NotImplementedError */")
                } catch (e: Exception) {
                    println("  /* Error: ${e.message} */")
                }
            }
        }
    }

    @Test
    fun testSpecificFile() {
        val abcFile = File("/home/orz/project/unitTest/test.abc")
        if (!abcFile.exists()) {
            println("Test file not found: ${abcFile.absolutePath}")
            return
        }
        
        println("Testing: ${abcFile.name}")
        println("\nSource code:")
        println("""
            class A {
                loop(i, j){
                    if(i < j){
                        for(; i < 100; i++){
                            for(; j < 100; j++){
                                if(j % 2 == 0) i++
                                if(i + j == 50) break
                            }
                        }
                    }else if(i > j){
                        for(; i + j < 1000; i--, j -= 2){
                            console.log(i+j)
                        }
                    }else {
                        console.log(i * j)
                    }
                }
            }
        """.trimIndent())
        
        println("\n${"=".repeat(60)}")
        println("Decompiled output:")
        println("=".repeat(60))
        
        val mmap = FileChannel.open(abcFile.toPath())
            .map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))
        
        for ((offset, classItem) in abc.classes) {
            if (classItem !is AbcClass) continue
            
            println("\nClass: ${classItem.name}")
            for (method in classItem.methods) {
                val code = method.codeItem
                if (code == null) {
                    println("  Method: ${method.name} (no code)")
                    continue
                }
                
                println("\n// Method: ${method.name}")
                try {
                    val asm = Asm(code)
                    val result = StructuredDecompiler.decompile(asm)
                    println(result)
                } catch (e: Exception) {
                    println("    Failed: ${e.message}")
                }
            }
        }
    }
    
    @Test
    fun testComplexFile() {
        val abcFile = File(testAbcDir, "complex_test.abc")
        if (!abcFile.exists()) {
            println("File not found: ${abcFile.absolutePath}")
            return
        }

        val sourceFile = File("/home/orz/project/unitTest/isa/isa_dynamic/complex_test/complex_test.js")
        if (sourceFile.exists()) {
            println("=== Source Code ===")
            println(sourceFile.readText())
        }

        println("\n${"=".repeat(60)}")
        println("Decompiled Output:")
        println("=".repeat(60))

        val mmap = FileChannel.open(abcFile.toPath())
            .map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))

        for ((offset, classItem) in abc.classes) {
            if (classItem !is AbcClass) continue

            println("\n// Class: ${classItem.name}")
            for (method in classItem.methods) {
                val code = method.codeItem
                if (code == null) {
                    println("  // Method: ${method.name} (no code)")
                    continue
                }

                println("\n// Method: ${method.name}")
                try {
                    val asm = Asm(code)
                    val result = StructuredDecompiler.decompile(asm)
                    println(result)
                } catch (e: ToJs.UnImplementedError) {
                    println("  /* unimplemented: ${e.item.asmName} */")
                } catch (e: NotImplementedError) {
                    println("  /* NotImplementedError */")
                } catch (e: Exception) {
                    println("  /* Error: ${e.message} */")
                }
            }
        }
    }

    @Test
    fun testDumpCfg() {
        val abcFile = File("/home/orz/project/unitTest/test.abc")
        if (!abcFile.exists()) {
            println("File not found")
            return
        }

        val mmap = FileChannel.open(abcFile.toPath())
            .map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))

        for ((offset, classItem) in abc.classes) {
            if (classItem !is AbcClass) continue

            println("Class: ${classItem.name}")
            for (method in classItem.methods) {
                println("  Method: ${method.name}")
                val code = method.codeItem ?: continue

                val asm = Asm(code)
                val codeSegments = CodeSegment.genGraph(asm)
                val builder = RegionGraphBuilder(codeSegments)
                val (regionGraph, entryRegion) = builder.build()

                println("    RegionGraph: ${regionGraph.nodes.size} nodes, entry=$entryRegion")
                for (region in regionGraph.nodes.sortedBy { it.name }) {
                    val succs = regionGraph.successors(region).map { "${it.name}(${regionGraph.edgeValue(region, it)})" }
                    val preds = regionGraph.predecessors(region).map { it.name }
                    println("    ${region.name} [${region.type}] preds=$preds succs=$succs")
                }

                try {
                    val analysis = StructureAnalysis(regionGraph, entryRegion, entryRegion)
                    val result = analysis.analyze()
                    println("    OK: $result")
                } catch (e: Exception) {
                    println("    FAILED: ${e.message}")
                    println("    Remaining nodes: ${regionGraph.nodes.size}")
                    for (region in regionGraph.nodes.sortedBy { it.name }) {
                        val succs = regionGraph.successors(region).map { "${it.name}(${regionGraph.edgeValue(region, it)})" }
                        val preds = regionGraph.predecessors(region).map { it.name }
                        println("      ${region.name} [${region.type}] preds=$preds succs=$succs")
                    }
                }
                println()
            }
        }
    }

    @Test
    fun testSampleDecompilation() {
        val files = listOf(
            "call_dynamic.abc",
            "getunmappedargs_dynamic.abc",
            "gettemplateobject_dynamic.abc"
        )

        for (fileName in files) {
            val abcFile = File(testAbcDir, fileName)
            if (!abcFile.exists()) {
                println("File not found: ${abcFile.absolutePath}")
                continue
            }

            println("\n=== $fileName ===")
            val mmap = FileChannel.open(abcFile.toPath())
                .map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
            val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))

            for ((offset, classItem) in abc.classes) {
                if (classItem !is AbcClass) continue

                for (method in classItem.methods) {
                    val code = method.codeItem ?: continue
                    val methodName = method.name
                    if (!methodName.startsWith("func")) continue

                    println("\n// Method: $methodName")
                    try {
                        val asm = Asm(code)
                        val result = StructuredDecompiler.decompile(asm)
                        println(result)
                    } catch (e: Exception) {
                        println("  Failed: ${e.message}")
                    }
                }
            }
        }
    }

    @Test
    fun scanCopyRestArgs() {
        val unitTestDir = File("/Users/vv/project/unitTest")
        val abcFiles = unitTestDir.walkTopDown().filter { it.isFile && it.extension == "abc" }.toList()
        println("Scanning ${abcFiles.size} ABC files for copyrestargs...")

        var filesWithCopyRest = 0
        var methodsWithCopyRest = 0
        val examples = mutableListOf<String>()

        for (abcFile in abcFiles) {
            try {
                val mmap = FileChannel.open(abcFile.toPath())
                    .map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
                val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))

                for ((offset, classItem) in abc.classes) {
                    if (classItem !is AbcClass) continue

                    for (method in classItem.methods) {
                        val code = method.codeItem ?: continue
                        val asm = Asm(code)
                        val hasCopyRest = asm.irOpList.any { it is IrOp.CopyRestArgs }
                        if (hasCopyRest) {
                            filesWithCopyRest++
                            methodsWithCopyRest++
                            if (examples.size < 10) {
                                examples.add("${abcFile.name}/${method.name}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("Error scanning ${abcFile.name}: ${e.message}")
            }
        }

        println("Files with copyrestargs: $filesWithCopyRest")
        println("Methods with copyrestargs: $methodsWithCopyRest")
        if (examples.isNotEmpty()) {
            println("Examples:")
            examples.forEach { println("  $it") }
        }
    }

    @Test
    fun scanDebugInfoParams() {
        val unitTestDir = File("/Users/vv/project/unitTest")
        val abcFiles = unitTestDir.walkTopDown().filter { it.isFile && it.extension == "abc" }.toList()
        println("Scanning ${abcFiles.size} ABC files for debug parameter names...")

        var filesWithParams = 0
        var methodsWithParams = 0
        val examples = mutableListOf<String>()

        for (abcFile in abcFiles) {
            try {
                val mmap = FileChannel.open(abcFile.toPath())
                    .map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
                val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))

                for ((offset, classItem) in abc.classes) {
                    if (classItem !is AbcClass) continue

                    for (method in classItem.methods) {
                        val abcMethod = method as? AbcMethod ?: continue
                        val params = abcMethod.debugInfo?.info?.params
                        if (!params.isNullOrEmpty()) {
                            filesWithParams++
                            methodsWithParams++
                            if (examples.size < 10) {
                                examples.add("${abcFile.name}/${method.name}: $params")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("Error scanning ${abcFile.name}: ${e.message}")
            }
        }

        println("Files with param names: $filesWithParams")
        println("Methods with param names: $methodsWithParams")
        if (examples.isNotEmpty()) {
            println("Examples:")
            examples.forEach { println("  $it") }
        }
    }

    @Test
    fun testSimpleAbc() {
        val abcFile = File(testAbcDir, "emptyobj.abc")
        if (!abcFile.exists()) {
            println("Test file not found: ${abcFile.absolutePath}")
            return
        }
        
        println("Testing simple ABC: ${abcFile.name}")
        val mmap = FileChannel.open(abcFile.toPath())
            .map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))
        
        for ((offset, classItem) in abc.classes) {
            if (classItem !is AbcClass) continue
            
            println("\nClass: ${classItem.name}")
            for (method in classItem.methods) {
                val code = method.codeItem
                if (code == null) {
                    println("  Method: ${method.name} (no code)")
                    continue
                }
                
                println("  Method: ${method.name}")
                try {
                    val asm = Asm(code)
                    val result = StructuredDecompiler.decompile(asm)
                    println("    Success!")
                    println(result)
                } catch (e: ToJs.UnImplementedError) {
                    println("    Unimplemented bytecode: ${e.item.asmName}")
                } catch (e: Exception) {
                    println("    Failed: ${e.message}")
                }
            }
        }
    }
    
    @Test
    fun testRegionGraph() {
        // 测试 RegionGraph 数据结构
        val graph = RegionGraph<String>()
        graph.addNode("A")
        graph.addNode("B")
        graph.addNode("C")
        graph.putEdgeValue("A", "B", true)
        graph.putEdgeValue("A", "C", false)
        graph.putEdgeValue("B", "C", true)
        
        assert(graph.nodes.size == 3) { "Expected 3 nodes" }
        assert(graph.successors("A").size == 2) { "Expected 2 successors for A" }
        assert(graph.predecessors("C").size == 2) { "Expected 2 predecessors for C" }
        assert(graph.edgeValue("A", "B") == true) { "Expected true edge from A to B" }
        
        println("RegionGraph test passed!")
    }
    
    @Test
    fun testDominatorGraph() {
        // 测试 DominatorGraph
        val graph = RegionGraph<String>()
        graph.addNode("entry")
        graph.addNode("A")
        graph.addNode("B")
        graph.addNode("C")
        graph.addNode("exit")
        
        graph.putEdgeValue("entry", "A", false)
        graph.putEdgeValue("A", "B", true)
        graph.putEdgeValue("A", "C", false)
        graph.putEdgeValue("B", "exit", false)
        graph.putEdgeValue("C", "exit", false)
        
        val doms = DominatorGraph(graph, "entry")
        
        assert(doms.dominatesStrictly("entry", "A")) { "entry should dominate A" }
        assert(doms.dominatesStrictly("entry", "B")) { "entry should dominate B" }
        assert(doms.dominatesStrictly("entry", "C")) { "entry should dominate C" }
        assert(doms.dominatesStrictly("entry", "exit")) { "entry should dominate exit" }
        assert(!doms.dominatesStrictly("A", "entry")) { "A should not dominate entry" }
        
        println("DominatorGraph test passed!")
    }
    
    @Test
    fun testLoopFinder() {
        // 测试 LoopFinder
        val graph = RegionGraph<String>()
        graph.addNode("entry")
        graph.addNode("header")
        graph.addNode("body")
        graph.addNode("latch")
        graph.addNode("exit")
        
        graph.putEdgeValue("entry", "header", false)
        graph.putEdgeValue("header", "body", true)
        graph.putEdgeValue("header", "exit", false)
        graph.putEdgeValue("body", "latch", false)
        graph.putEdgeValue("latch", "header", true)  // 回边
        
        val doms = DominatorGraph(graph, "entry")
        val loopFinder = LoopFinder(graph, "header", doms)
        
        assert("header" in loopFinder.loopNodes) { "header should be in loop" }
        assert("body" in loopFinder.loopNodes) { "body should be in loop" }
        assert("latch" in loopFinder.loopNodes) { "latch should be in loop" }
        assert("entry" !in loopFinder.loopNodes) { "entry should not be in loop" }
        assert("exit" !in loopFinder.loopNodes) { "exit should not be in loop" }
        
        println("LoopFinder test passed!")
    }
}
