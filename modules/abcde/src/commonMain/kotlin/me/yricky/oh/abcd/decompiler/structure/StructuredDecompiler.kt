package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.cfm.AbcMethod
import me.yricky.oh.abcd.cfm.argsStr
import me.yricky.oh.abcd.decompiler.CodeSegment
import me.yricky.oh.abcd.decompiler.ToJs
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.abcd.isa.calledMethods
import me.yricky.oh.abcd.isa.calledStrings

/**
 * 结构化反编译器统一入口
 * 将 ABCDE 的 Asm 反汇编结果转换为结构化的 JavaScript/TypeScript 代码
 */
object StructuredDecompiler {

    /** 返回给 LLM 的部分代码最大行数，避免无法完整反编译的方法占用过多上下文 */
    const val MAX_DISPLAY_OUTPUT_LINES = 100

    /**
     * 反编译单个方法
     * @param asm 方法的反汇编结果
     * @return 反编译后的代码字符串
     */
    fun decompile(asm: Asm): String {
        return try {
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
            generateCode(structuredRegion as Region, asm)
        } catch (e: OutputTooLargeException) {
            // 输出超出预算：返回部分代码 + 方法摘要，避免降级到线性反编译再次 OOM
            formatTruncatedOutput(asm, e)
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
        } catch (e: OutputTooLargeException) {
            val summary = MethodSummary.from(asm, e.generatedChars)
            DecompileResult(
                success = false,
                code = formatTruncatedOutput(asm, e),
                regionCount = 0,
                method = "truncated",
                summary = summary
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
     * 组装截断后的最终输出：摘要注释 + 部分代码（总输出控制在 [MAX_DISPLAY_OUTPUT_LINES] 行以内）
     */
    private fun formatTruncatedOutput(asm: Asm, e: OutputTooLargeException): String {
        val summary = MethodSummary.from(asm, e.generatedChars)
        val summaryText = buildString {
            appendLine("// [method too large, generated ${summary.totalGeneratedChars} chars total]")
            appendLine("// Method summary:")
            appendLine("//   decodedName: ${summary.decodedName}")
            appendLine("//   args: ${summary.args}")
            appendLine("//   instructions: ${summary.instructionCount}")
            appendLine("//   basicBlocks: ${summary.basicBlockCount}")
            appendLine("//   vregs: ${summary.vregCount}")
            appendLine("//   hasTryCatch: ${summary.hasTryCatch}")
            appendLine("//   hasRestArgs: ${summary.hasRestArgs}")
            appendLine("//   uniqueStrings: ${summary.uniqueStringCount}")
            appendLine("//   uniqueCalledMethods: ${summary.uniqueCalledMethodCount}")
            appendLine("//   topStrings: ${summary.topStrings}")
            appendLine("//   topCalledMethods: ${summary.topCalledMethods}")
        }
        val summaryLines = summaryText.lines().size
        val partialBudget = (MAX_DISPLAY_OUTPUT_LINES - summaryLines - 1).coerceAtLeast(0)
        val partialLines = (e.partialOutput ?: "").lines()
        val displayedLines = partialLines.take(partialBudget)
        val partial = displayedLines.joinToString("\n")
        return buildString {
            append(summaryText)
            if (partial.isNotEmpty()) {
                appendLine()
                appendLine("// [displaying first ${displayedLines.size} lines / ${partial.length} chars of partial code]")
                append(partial)
            }
        }
    }

    /**
     * 反编译结果
     */
    data class DecompileResult(
        val success: Boolean,
        val code: String,
        val regionCount: Int,
        val method: String,
        val summary: MethodSummary? = null
    )

    /**
     * 超大方法的摘要信息，用于在无法给出完整反编译结果时帮助 LLM 理解方法规模与内容
     */
    data class MethodSummary(
        val decodedName: String,
        val args: String,
        val instructionCount: Int,
        val basicBlockCount: Int,
        val vregCount: Int,
        val hasTryCatch: Boolean,
        val hasRestArgs: Boolean,
        val uniqueStringCount: Int,
        val topStrings: List<String>,
        val uniqueCalledMethodCount: Int,
        val topCalledMethods: List<String>,
        val totalGeneratedChars: Int
    ) {
        companion object {
            private const val TOP_N = 20

            fun from(asm: Asm, totalGeneratedChars: Int = 0): MethodSummary {
                val method = asm.code.method
                val decodedName = decodeMethodName(method)
                val restIndex = asm.irOpList.filterIsInstance<IrOp.CopyRestArgs>().firstOrNull()?.startIdx ?: -1
                val args = method.argsStr(restIndex)

                val codeSegments = CodeSegment.genGraph(asm)
                val basicBlockCount = codeSegments.size

                val allStrings = asm.list.flatMap { it.calledStrings.asIterable() }
                val uniqueStrings = allStrings.distinct()
                val topStrings = uniqueStrings
                    .sortedByDescending { it.length }
                    .take(TOP_N)

                val allMethods = asm.list.flatMap { it.calledMethods.asIterable() }
                val uniqueMethods = allMethods.distinct()
                val topCalledMethods = uniqueMethods
                    .map { "${it.clazz?.name ?: "?"}.${decodeMethodName(it)}" }
                    .distinct()
                    .take(TOP_N)

                return MethodSummary(
                    decodedName = decodedName,
                    args = args,
                    instructionCount = asm.list.size,
                    basicBlockCount = basicBlockCount,
                    vregCount = asm.code.numVRegs,
                    hasTryCatch = asm.code.tryBlocks.isNotEmpty(),
                    hasRestArgs = asm.irOpList.any { it is IrOp.CopyRestArgs },
                    uniqueStringCount = uniqueStrings.size,
                    topStrings = topStrings,
                    uniqueCalledMethodCount = uniqueMethods.size,
                    topCalledMethods = topCalledMethods,
                    totalGeneratedChars = totalGeneratedChars
                )
            }
        }
    }
}
