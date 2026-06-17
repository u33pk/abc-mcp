package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.cfm.AbcMethod
import me.yricky.oh.abcd.cfm.argsStr
import me.yricky.oh.abcd.decompiler.CodeSegment
import me.yricky.oh.abcd.decompiler.ToJs
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.decompiler.structure.reconstruction.ClassReconstructionPass
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.abcd.isa.calledMethods
import me.yricky.oh.abcd.isa.calledStrings
import me.yricky.oh.common.value

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
            // 1. 构建 CFG（try/catch 边界已作为独立基本块切分）
            val codeSegments = CodeSegment.genGraph(asm)

            // 2. 构建 RegionGraph（catch handler 从主图剥离）
            val builder = RegionGraphBuilder(codeSegments, asm.code.tryBlocks)
            val buildResult = builder.build()

            // 3. 执行结构化分析（仅对主控制流图）
            val analysis = StructureAnalysis(
                regionGraph = buildResult.graph,
                entryRegion = buildResult.entry,
                lastRegion = buildResult.entry // 简化处理
            )
            val structuredRegion = analysis.analyze()

            // 4. 生成代码（主图 + 单独标注的 catch handler）
            generateCode(structuredRegion as Region, asm, buildResult.catchHandlers)
        } catch (e: OutputTooLargeException) {
            // 输出超出预算：返回部分代码 + 方法摘要，避免降级到线性反编译再次 OOM
            formatTruncatedOutput(asm, e)
        } catch (e: Exception) {
            // 结构化分析失败时，仍按基本块顺序输出，并保留 try/catch 标注
            return "// Structure analysis failed: ${e.message}\n" +
                   "// Falling back to linear block decompilation\n" +
                   try {
                       val fallbackSegments = CodeSegment.genGraph(asm)
                       val fallbackBuilder = RegionGraphBuilder(fallbackSegments, asm.code.tryBlocks)
                       val fallbackResult = fallbackBuilder.build()
                       val mainBlocks = fallbackResult.graph.nodes.mapNotNull { it.block }
                       StructuredToJs(asm, fallbackResult.catchHandlers).generateFallback(mainBlocks)
                   } catch (e2: Exception) {
                       "// Linear block decompilation also failed: ${e2.message}"
                   }
        }
    }

    /**
     * 反编译并返回详细信息
     */
    fun decompileWithInfo(asm: Asm): DecompileResult {
        return try {
            val codeSegments = CodeSegment.genGraph(asm)
            val builder = RegionGraphBuilder(codeSegments, asm.code.tryBlocks)
            val buildResult = builder.build()

            val analysis = StructureAnalysis(
                regionGraph = buildResult.graph,
                entryRegion = buildResult.entry,
                lastRegion = buildResult.entry
            )
            val structuredRegion = analysis.analyze()

            DecompileResult(
                success = true,
                code = generateCode(structuredRegion as Region, asm, buildResult.catchHandlers),
                regionCount = buildResult.graph.nodes.size,
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
     * 对指定方法执行 class 重组并返回识别出的类（不生成代码）。
     * 主要用于 MCP 工具在 get_class_detail 中展示 func_main_0 里的 ArkTS class。
     */
    fun reconstructClasses(asm: Asm): List<me.yricky.oh.abcd.decompiler.structure.reconstruction.ReconstructedClass> {
        return try {
            val codeSegments = CodeSegment.genGraph(asm)
            val builder = RegionGraphBuilder(codeSegments, asm.code.tryBlocks)
            val buildResult = builder.build()
            val analysis = StructureAnalysis(
                regionGraph = buildResult.graph,
                entryRegion = buildResult.entry,
                lastRegion = buildResult.entry
            )
            val structuredRegion = analysis.analyze() as Region
            if (asm.code.method.name == AbcClass.ENTRY_FUNC_NAME) {
                ClassReconstructionPass.reconstruct(structuredRegion)
                    .map { it.clazz }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 从 Region 树生成代码
     */
    private fun generateCode(
        region: Region,
        asm: Asm,
        catchHandlers: List<RegionGraphBuilder.CatchHandlerInfo> = emptyList()
    ): String {
        // 对模块入口函数 func_main_0 执行 class 重组
        if (asm.code.method.name == AbcClass.ENTRY_FUNC_NAME) {
            ClassReconstructionPass.reconstruct(region)
        }
        val generator = StructuredToJs(asm, catchHandlers)
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
            if (summary.hasTryCatch) {
                appendLine("//   tryCatchSummary: ${summary.tryCatchSummary}")
            }
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
        val tryCatchSummary: String,
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

                val tryCatchSummary = asm.code.tryBlocks.joinToString("; ") { tryBlock ->
                    val catches = tryBlock.catchBlocks.joinToString(", ") { catchBlock ->
                        val catchType = try {
                            asm.code.abc.stringItem(catchBlock.typeIdx).value
                                .takeIf { it.isNotEmpty() } ?: "typeIdx=${catchBlock.typeIdx}"
                        } catch (_: Exception) {
                            "typeIdx=${catchBlock.typeIdx}"
                        }
                        "$catchType@[0x${catchBlock.handlerPc.toString(16)},0x${(catchBlock.handlerPc + catchBlock.codeSize).toString(16)})"
                    }
                    "try[0x${tryBlock.startPc.toString(16)},0x${(tryBlock.startPc + tryBlock.length).toString(16)}) -> $catches"
                }

                return MethodSummary(
                    decodedName = decodedName,
                    args = args,
                    instructionCount = asm.list.size,
                    basicBlockCount = basicBlockCount,
                    vregCount = asm.code.numVRegs,
                    hasTryCatch = asm.code.tryBlocks.isNotEmpty(),
                    tryCatchSummary = tryCatchSummary,
                    hasRestArgs = asm.irOpList.any { op -> op is IrOp.AssignReg && op.right is IrOp.CopyRestArgs },
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
