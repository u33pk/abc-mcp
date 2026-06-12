package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.decompiler.CodeSegment
import me.yricky.oh.abcd.decompiler.ToJs
import me.yricky.oh.abcd.isa.Asm

/**
 * 结构化反编译器统一入口
 * 将 ABCDE 的 Asm 反汇编结果转换为结构化的 JavaScript/TypeScript 代码
 */
object StructuredDecompiler {

    /**
     * 反编译单个方法
     * @param asm 方法的反汇编结果
     * @return 反编译后的代码字符串
     */
    fun decompile(asm: Asm): String {
        try {
            // 1. 构建 CFG
            val codeSegments = CodeSegment.genGraph(asm)
            
            // 2. 构建 RegionGraph
            val builder = RegionGraphBuilder(codeSegments)
            val (regionGraph, entryRegion) = builder.build()
            
            // 3. 执行结构化分析
            val analysis = StructureAnalysis(
                regionGraph = regionGraph,
                entryRegion = entryRegion,
                lastRegion = entryRegion // 简化处理
            )
            val structuredRegion = analysis.analyze()
            
            // 4. 生成代码
            return generateCode(structuredRegion as Region, asm)
        } catch (e: Exception) {
            // 降级到旧的反编译器
            return "// Structure analysis failed: ${e.message}\n" +
                   "// Falling back to linear decompilation\n" +
                   try {
                       ToJs(asm).toJS()
                   } catch (e2: Exception) {
                       "// Linear decompilation also failed: ${e2.message}"
                   }
        }
    }

    /**
     * 反编译并返回详细信息
     */
    fun decompileWithInfo(asm: Asm): DecompileResult {
        return try {
            val codeSegments = CodeSegment.genGraph(asm)
            val builder = RegionGraphBuilder(codeSegments)
            val (regionGraph, entryRegion) = builder.build()
            
            val analysis = StructureAnalysis(
                regionGraph = regionGraph,
                entryRegion = entryRegion,
                lastRegion = entryRegion
            )
            val structuredRegion = analysis.analyze()
            
            DecompileResult(
                success = true,
                code = generateCode(structuredRegion as Region, asm),
                regionCount = regionGraph.nodes.size,
                method = "structured"
            )
        } catch (e: Exception) {
            DecompileResult(
                success = false,
                code = "// Error: ${e.message}",
                regionCount = 0,
                method = "error"
            )
        }
    }

    /**
     * 从 Region 树生成代码
     */
    private fun generateCode(region: Region, asm: Asm): String {
        val generator = StructuredToJs(asm)
        return generator.generate(region)
    }

    /**
     * 反编译结果
     */
    data class DecompileResult(
        val success: Boolean,
        val code: String,
        val regionCount: Int,
        val method: String
    )
}
