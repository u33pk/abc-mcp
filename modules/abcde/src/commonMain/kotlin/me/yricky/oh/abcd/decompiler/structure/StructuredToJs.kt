package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.cfm.AbcMethod
import me.yricky.oh.abcd.cfm.argsStr
import me.yricky.oh.abcd.code.TryBlock
import me.yricky.oh.abcd.decompiler.CodeSegment
import me.yricky.oh.abcd.decompiler.behaviour.FunSimCtx
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.decompiler.structure.LexEnvNameResolver.buildLexEnvStacks
import me.yricky.oh.abcd.decompiler.structure.LexEnvNameResolver.isLexEnvOp
import me.yricky.oh.abcd.decompiler.structure.lexLvlSlot
import me.yricky.oh.abcd.decompiler.structure.LexEnvNameResolver.resolveLexName
import me.yricky.oh.abcd.decompiler.structure.statement.DoWhileStatement
import me.yricky.oh.abcd.decompiler.structure.statement.IfStatement
import me.yricky.oh.abcd.decompiler.structure.statement.WhileStatement
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.abcd.isa.asmName
import me.yricky.oh.abcd.literal.ModuleLiteralArray
import me.yricky.oh.common.value

/**
 * 解码方法名为可读格式
 * #*#foo → foo
 * #~A=#A → constructor
 * #~A>#loop → loop
 * #~A<#foo → foo
 *
 * 同时剥离方舟编译器生成的变体编号后缀（如 foo^1 → foo）。
 * 匿名函数不再显示为 [ANONYMOUS]，而是生成稳定的短标识符 anon_xxxxxxxx。
 */
fun decodeMethodName(method: AbcMethod): String {
    val scopeInfo = AbcMethod.ScopeInfo.parseFromMethod(method)
    val raw = if (scopeInfo != null) {
        scopeInfo.decorateMethodName(method)
    } else {
        method.name
    }
    val withoutVariant = raw.replace(Regex("\\^[0-9]+$"), "")
    return if (withoutVariant.isEmpty() || withoutVariant == AbcMethod.ScopeInfo.ANONYMOUS_NAME) {
        generateAnonymousName(method)
    } else {
        withoutVariant
    }
}

/**
 * 为匿名函数生成稳定的短标识符。
 * 基于方法原始名、类名和 offset，确保同一方法始终得到同一名字，不同方法尽量唯一。
 */
private fun generateAnonymousName(method: AbcMethod): String {
    val input = "${method.name}|${method.offset}|${method.clazz?.name ?: ""}"
    val hash = input.fold(0L) { acc, c -> (acc * 31 + c.code) and 0xffffffffL }
    return "anon_${hash.toString(16).padStart(8, '0')}"
}

/**
 * 输出超过预算时抛出此异常，由 generate() 顶层捕获并返回截断提示
 * @param generatedChars 异常发生前已生成的字符数
 * @param partialOutput 已生成的部分输出，用于在截断时仍返回可用内容
 */
class OutputTooLargeException(
    val generatedChars: Int,
    val partialOutput: String? = null
) : Exception("Decompiler output exceeded budget")

/**
 * 基于结构化分析的代码生成器
 * 将 Region 树转换为 JavaScript/TypeScript 代码
 */
class StructuredToJs(
    val asm: Asm,
    private val catchHandlers: List<RegionGraphBuilder.CatchHandlerInfo> = emptyList()
) {
    companion object {
        /** 单个方法反编译输出的最大字符数（10 MB） */
        const val MAX_TOTAL_OUTPUT_SIZE = 10 * 1024 * 1024
        /** 字面量数组/对象最多完整展示的元素/键值对数量 */
        const val MAX_LITERAL_ARRAY_SIZE = 1000
    }

    private val imports = mutableListOf<ModuleLiteralArray.RegularImport>()
    private val nsImports = mutableListOf<ModuleLiteralArray.NamespaceImport>()
    private val exports = mutableSetOf<ModuleLiteralArray.LocalExport>()
    private var outputSize = 0

    /**
     * 每个原始指令位置之前的词法环境栈快照
     */
    private val perOpStacks = buildLexEnvStacks(asm.irOpList)

    /**
     * 基本块起始偏移 -> 进入块时的词法环境栈
     */
    private val blockStartStack = asm.list.associate { it.codeOffset to perOpStacks[it.index] }

    /**
     * 当前正在生成的指令所处的词法环境栈
     */
    private var currentLexEnvStack: List<List<String?>> = emptyList()

    /**
     * 检查继续追加 [additional] 个字符是否会超过预算
     */
    private fun checkBudget(additional: Int) {
        if (outputSize + additional > MAX_TOTAL_OUTPUT_SIZE) {
            throw OutputTooLargeException(outputSize)
        }
    }

    /**
     * 在预算内追加文本到 StringBuilder
     */
    private fun StringBuilder.safeAppend(text: String) {
        checkBudget(text.length)
        append(text)
        outputSize += text.length
    }

    /**
     * 生成完整的函数代码
     * @return 生成的 JS 代码；若超出预算，会把当前已生成内容附加到 [OutputTooLargeException.partialOutput] 后重新抛出
     */
    fun generate(region: Region): String {
        val sb = StringBuilder()
        return try {
            // 生成函数头（使用解码后的方法名）
            val methodName = decodeMethodName(asm.code.method)
            val restIndex = asm.irOpList.mapNotNull { op ->
                (op as? IrOp.AssignReg)?.right as? IrOp.CopyRestArgs
            }.firstOrNull()?.startIdx ?: -1
            val isAsync = asm.irOpList.any { it is IrOp.AsyncFunctionEnter }
            sb.safeAppend("${if (isAsync) "async " else ""}function $methodName${asm.code.method.argsStr(restIndex)} {\n")

            // 在函数体顶部输出 try-catch 摘要，确保即使后续代码被截断或条件块未直接生成，LLM 也能看到完整的 try/catch 边界
            generateTryCatchHeader(sb, 1)

            // 生成函数体（直接写入 sb，确保超出预算时保留尽可能多的部分输出）
            generateRegion(region, sb, 1)

            // 生成 catch handler 注释块（catch handler 从主 CFG 剥离，单独输出）
            generateCatchHandlers(sb, 1)

            // 生成函数尾
            sb.safeAppend("}")

            // 添加导入语句
            val importStr = imports.distinctBy { it.localName }.joinToString(separator = ";\n") { it.toString() }
            val nsImportStr = nsImports.joinToString(separator = ";\n") { it.toString() }

            // 添加导出语句
            val exportStr = exports.mapNotNull { exp ->
                val local = exp.localName ?: return@mapNotNull null
                val export = exp.exportName ?: return@mapNotNull null
                if (local == export) "export { $local }" else "export { $local as $export }"
            }.distinct().joinToString(separator = ";\n")

            val header = listOfNotNull(
                importStr.ifBlank { null },
                nsImportStr.ifBlank { null },
                exportStr.ifBlank { null }
            ).joinToString(separator = ";\n")

            if (header.isNotBlank()) "$header;\n\n$sb" else sb.toString()
        } catch (e: OutputTooLargeException) {
            // 把当前已生成的部分输出附加到异常中，供上层拼接摘要后返回给 LLM
            throw OutputTooLargeException(e.generatedChars, sb.toString())
        }
    }

    /**
     * 结构化分析失败时的降级输出：按字节码偏移顺序依次输出主 CFG 中的基本块，
     * 并附加 catch handler 注释。这样即使无法还原 if/while 结构，LLM 仍能看到 try/catch 边界。
     */
    fun generateFallback(blocks: List<CodeSegment.BasicBlock>): String {
        val sb = StringBuilder()
        return try {
            val methodName = decodeMethodName(asm.code.method)
            val restIndex = asm.irOpList.mapNotNull { op ->
                (op as? IrOp.AssignReg)?.right as? IrOp.CopyRestArgs
            }.firstOrNull()?.startIdx ?: -1
            val isAsync = asm.irOpList.any { it is IrOp.AsyncFunctionEnter }
            sb.safeAppend("${if (isAsync) "async " else ""}function $methodName${asm.code.method.argsStr(restIndex)} {\n")

            // 在函数体顶部输出 try-catch 摘要
            generateTryCatchHeader(sb, 1)

            for (block in blocks.sortedBy { it.item.codeOffset }) {
                generateLinearBlock(block, sb, 1)
            }

            generateCatchHandlers(sb, 1)
            sb.safeAppend("}")

            val importStr = imports.distinctBy { it.localName }.joinToString(separator = ";\n") { it.toString() }
            val nsImportStr = nsImports.joinToString(separator = ";\n") { it.toString() }
            val exportStr = exports.mapNotNull { exp ->
                val local = exp.localName ?: return@mapNotNull null
                val export = exp.exportName ?: return@mapNotNull null
                if (local == export) "export { $local }" else "export { $local as $export }"
            }.distinct().joinToString(separator = ";\n")

            val header = listOfNotNull(
                importStr.ifBlank { null },
                nsImportStr.ifBlank { null },
                exportStr.ifBlank { null }
            ).joinToString(separator = ";\n")

            if (header.isNotBlank()) "$header;\n\n$sb" else sb.toString()
        } catch (e: OutputTooLargeException) {
            throw OutputTooLargeException(e.generatedChars, sb.toString())
        }
    }

    /**
     * 生成区域代码，直接追加到 [out]
     */
    private fun generateRegion(region: Region, out: StringBuilder, indent: Int, depth: Int = 0) {
        if (depth > 100) {
            out.safeAppend("${"    ".repeat(indent)}// [depth limit reached]\n")
            return
        }
        val indentStr = "    ".repeat(indent)

        for (statement in region.statements) {
            when (statement) {
                is IfStatement -> {
                    val activeTries = findConditionTryBlocks(statement.condition)
                    emitTryAnnotations(activeTries, out, indent)
                    val condStr = generateCondition(statement.condition)
                    out.safeAppend("${indentStr}if ($condStr) {\n")
                    if (statement.thenBranch != null) {
                        generateRegion(statement.thenBranch, out, indent + 1, depth + 1)
                    }
                    if (statement.elseBranch != null) {
                        out.safeAppend("${indentStr}} else {\n")
                        generateRegion(statement.elseBranch, out, indent + 1, depth + 1)
                    }
                    out.safeAppend("${indentStr}}\n")
                    emitTryEndAnnotations(activeTries, out, indent)
                }
                is WhileStatement -> {
                    val activeTries = statement.condition?.let { findConditionTryBlocks(it) } ?: emptyList()
                    emitTryAnnotations(activeTries, out, indent)
                    if (statement.condition == null) {
                        out.safeAppend("${indentStr}while (true) {\n")
                    } else {
                        val condStr = generateCondition(statement.condition)
                        out.safeAppend("${indentStr}while ($condStr) {\n")
                    }
                    generateRegion(statement.body, out, indent + 1, depth + 1)
                    out.safeAppend("${indentStr}}\n")
                    emitTryEndAnnotations(activeTries, out, indent)
                }
                is DoWhileStatement -> {
                    out.safeAppend("${indentStr}do {\n")
                    generateRegion(statement.body, out, indent + 1, depth + 1)
                    out.safeAppend("${indentStr}} while (/* condition */);\n")
                }
                is LinearStatement -> {
                    generateLinearBlock(statement.block, out, indent)
                }
                else -> {
                    out.safeAppend("${indentStr}// unknown statement type\n")
                }
            }
        }
    }

    /**
     * 获取指定基本块处于的 try 作用域列表（按起始地址升序，支持嵌套）
     */
    private fun activeTryBlocksForBlock(block: CodeSegment.BasicBlock?): List<TryBlock> {
        val offset = block?.item?.codeOffset ?: return emptyList()
        return asm.code.tryBlocks
            .filter { it.startPc <= offset && offset < it.startPc + it.length }
            .sortedBy { it.startPc }
    }

    private fun emitTryAnnotations(tryBlocks: List<TryBlock>, out: StringBuilder, indent: Int) {
        val indentStr = "    ".repeat(indent)
        for (tryBlock in tryBlocks) {
            val start = tryBlock.startPc
            val end = tryBlock.startPc + tryBlock.length
            out.safeAppend("${indentStr}// try [0x${start.toString(16)},0x${end.toString(16)})\n")
        }
    }

    private fun emitTryEndAnnotations(tryBlocks: List<TryBlock>, out: StringBuilder, indent: Int) {
        val indentStr = "    ".repeat(indent)
        for (tryBlock in tryBlocks.asReversed()) {
            val start = tryBlock.startPc
            val end = tryBlock.startPc + tryBlock.length
            out.safeAppend("${indentStr}// end try [0x${start.toString(16)},0x${end.toString(16)})\n")
        }
    }

    /**
     * 从可能经过包装的条件 Region 中递归找出真正的 InsCondition 基本块，
     * 并返回这些条件所处的 try 作用域并集。用于在 if/while 等语句前后补充 try 边界注释。
     */
    private fun findConditionTryBlocks(region: Region): List<TryBlock> {
        val result = mutableSetOf<TryBlock>()
        val visited = mutableSetOf<Region>()
        fun dfs(r: Region) {
            if (r in visited) return
            visited.add(r)
            val block = r.block
            if (block is CodeSegment.InsCondition) {
                result.addAll(activeTryBlocksForBlock(block))
            }
            for (stmt in r.statements) {
                when (stmt) {
                    is LinearStatement -> {
                        if (stmt.block is CodeSegment.InsCondition) {
                            result.addAll(activeTryBlocksForBlock(stmt.block))
                        }
                    }
                    is IfStatement -> {
                        stmt.thenBranch?.let { dfs(it) }
                        stmt.elseBranch?.let { dfs(it) }
                    }
                    is WhileStatement -> stmt.body?.let { dfs(it) }
                    is DoWhileStatement -> stmt.body?.let { dfs(it) }
                    else -> { /* no-op */ }
                }
            }
        }
        dfs(region)
        return result.sortedBy { it.startPc }
    }

    /**
     * 在函数体顶部输出 try-catch 摘要。
     * 这样即使后续代码被截断、结构化分析失败或 try 体只包含条件块，LLM 也能首先看到 try/catch 边界。
     */
    private fun generateTryCatchHeader(out: StringBuilder, indent: Int) {
        if (asm.code.tryBlocks.isEmpty()) return
        val indentStr = "    ".repeat(indent)
        out.safeAppend("${indentStr}// try-catch summary:\n")
        for (tryBlock in asm.code.tryBlocks) {
            val start = tryBlock.startPc
            val end = tryBlock.startPc + tryBlock.length
            out.safeAppend("${indentStr}// try [0x${start.toString(16)},0x${end.toString(16)})\n")
        }
        generateCatchHandlerComments(out, indent)
    }

    /**
     * 仅输出 catch handler 的注释行（不含 handler 代码）。
     */
    private fun generateCatchHandlerComments(out: StringBuilder, indent: Int) {
        if (catchHandlers.isEmpty()) return
        val indentStr = "    ".repeat(indent)
        for ((region, tryBlock, catchBlock) in catchHandlers) {
            val tryStart = tryBlock.startPc
            val tryEnd = tryBlock.startPc + tryBlock.length
            val handlerStart = catchBlock.handlerPc
            val handlerEnd = catchBlock.handlerPc + catchBlock.codeSize
            val catchType = resolveCatchType(catchBlock.typeIdx)
            out.safeAppend("${indentStr}// catch handler for try [0x${tryStart.toString(16)},0x${tryEnd.toString(16)}) type=$catchType [0x${handlerStart.toString(16)},0x${handlerEnd.toString(16)})\n")
        }
    }

    /**
     * 生成 catch handler 注释块（含 handler 代码）。
     * catch handler 只能由异常机制进入，因此从主 CFG 剥离后单独输出，避免干扰结构化分析。
     */
    private fun generateCatchHandlers(out: StringBuilder, indent: Int) {
        if (catchHandlers.isEmpty()) return
        val indentStr = "    ".repeat(indent)
        out.safeAppend("${indentStr}// ---------- catch handlers (exception-driven entry) ----------\n")
        for ((region, tryBlock, catchBlock) in catchHandlers) {
            val tryStart = tryBlock.startPc
            val tryEnd = tryBlock.startPc + tryBlock.length
            val handlerStart = catchBlock.handlerPc
            val handlerEnd = catchBlock.handlerPc + catchBlock.codeSize
            val catchType = resolveCatchType(catchBlock.typeIdx)
            out.safeAppend("${indentStr}// catch handler for try [0x${tryStart.toString(16)},0x${tryEnd.toString(16)}) type=$catchType [0x${handlerStart.toString(16)},0x${handlerEnd.toString(16)})\n")
            generateLinearBlock(region.block!!, out, indent)
            out.safeAppend("\n")
        }
    }

    /**
     * 将 catch 块中的 typeIdx 解析为可读的异常类型名，解析失败则回退显示索引值。
     */
    private fun resolveCatchType(typeIdx: Int): String {
        return try {
            asm.code.abc.stringItem(typeIdx).value.takeIf { it.isNotEmpty() } ?: "typeIdx=$typeIdx"
        } catch (_: Exception) {
            "typeIdx=$typeIdx"
        }
    }

    /**
     * 从条件 Region 中提取并生成条件表达式
     */
    private fun generateCondition(conditionRegion: Region): String {
        val block = conditionRegion.block
        if (block is CodeSegment.InsCondition) {
            val expr = block.condition
            return if (conditionRegion.conditionalExp) {
                generateExpression(expr)
            } else {
                "!(${generateExpression(expr)})"
            }
        }
        // 回退：尝试从 statements 中找条件
        for (stmt in conditionRegion.statements) {
            if (stmt is LinearStatement) {
                val b = stmt.block
                if (b is CodeSegment.InsCondition) {
                    val expr = b.condition
                    return if (conditionRegion.conditionalExp) {
                        generateExpression(expr)
                    } else {
                        "!(${generateExpression(expr)})"
                    }
                }
            }
        }
        return "/* condition at ${conditionRegion.name} */"
    }

    /**
     * 生成线性基本块的代码，直接追加到 [out]
     *
     * 在每个线性块前后标注它处于哪些 try 作用域内。由于 CFG 已在 try/catch 边界处切分，
     * 同一个块内的所有指令必然处于相同的 try 集合中，因此按块标注是准确且稳定的。
     */
    private fun generateLinearBlock(block: CodeSegment.BasicBlock, out: StringBuilder, indent: Int) {
        val indentStr = "    ".repeat(indent)
        val blockOff = block.item.codeOffset

        // 计算当前基本块处于哪些 try 范围内（支持嵌套）
        val activeTryBlocks = asm.code.tryBlocks
            .filter { it.startPc <= blockOff && blockOff < it.startPc + it.length }
            .sortedBy { it.startPc }

        // 打印 try 进入注释（外层先打印）
        for (tryBlock in activeTryBlocks) {
            val start = tryBlock.startPc
            val end = tryBlock.startPc + tryBlock.length
            out.safeAppend("${indentStr}// try [0x${start.toString(16)},0x${end.toString(16)})\n")
        }

        // 设置当前词法环境栈（单指令块直接使用该指令之前的栈）
        currentLexEnvStack = blockStartStack[block.item.codeOffset] ?: emptyList()

        when (block) {
            is CodeSegment.Linear -> {
                val items = block.toList()
                val containsLexEnvOps = items.any { isLexEnvOp(it.irOp) }

                if (containsLexEnvOps) {
                    // 块内含词法环境操作，按原始指令顺序逐条生成，确保名称解析精确
                    for (item in items) {
                        currentLexEnvStack = perOpStacks[item.index]
                        val irOp = item.irOp
                        if (irOp is IrOp.NOP || irOp is IrOp.JustAnno || isLexEnvOp(irOp)) continue
                        out.safeAppend("$indentStr${generateIrOp(irOp)}\n")
                    }
                } else {
                    // 收集所有指令并优化
                    val ops = items.map { it.irOp }
                    val liveOut = mutableSetOf<FunSimCtx.RegId>()
                    liveOut.add(FunSimCtx.RegId.ACC)
                    val optimizedOps = IrOpOptimizer.optimizeList(ops, liveOut)

                    for (irOp in optimizedOps) {
                        if (irOp is IrOp.NOP || irOp is IrOp.JustAnno) continue
                        out.safeAppend("$indentStr${generateIrOp(irOp)}\n")
                    }
                }
            }
            is CodeSegment.Return -> {
                out.safeAppend("$indentStr${generateIrOp(block.item.irOp)}\n")
            }
            is CodeSegment.JumpMark -> {
                // 无条件跳转，通常不需要生成代码
            }
            is CodeSegment.InsCondition -> {
                // 条件跳转，通常由 IfStatement 处理
            }
            is CodeSegment.Throw -> {
                out.safeAppend("$indentStr${generateIrOp(block.item.irOp)}\n")
            }
        }

        // 打印 try 退出注释（内层先结束）
        for (tryBlock in activeTryBlocks.asReversed()) {
            val start = tryBlock.startPc
            val end = tryBlock.startPc + tryBlock.length
            out.safeAppend("${indentStr}// end try [0x${start.toString(16)},0x${end.toString(16)})\n")
        }
    }

    /**
     * 生成 IrOp 的代码
     */
    private fun generateIrOp(op: IrOp): String {
        return when (op) {
            IrOp.Debugger -> "/* debugger */"
            IrOp.Deprecated -> "/* deprecated */"
            IrOp.Disabled -> "/* disabled */"
            IrOp.NOP -> ""
            IrOp.AsyncFunctionEnter -> ""
            is IrOp.NewLexEnv -> ""
            is IrOp.NewLexEnvWithName -> ""
            IrOp.PopLexEnv -> ""
            is IrOp.UnImplemented -> "/* unimplemented: ${op.item.asmName} */"
            is IrOp.JustAnno -> "/* ${op.anno} */"
            is IrOp.Statement -> {
                when (op) {
                    is IrOp.AssignReg -> "${generateRegId(op.left)} = ${generateExpression(op.right)};"
                    is IrOp.AssignObj -> "${generateExpression(op.left)} = ${generateExpression(op.right)};"
                    is IrOp.Jump -> "/* jump */"
                    is IrOp.JumpIf -> "/* jumpIf */"
                    is IrOp.Return -> "return ${if (op.hasValue) generateRegId(FunSimCtx.RegId.ACC) else "undefined"};"
                    IrOp.Throw.Acc -> "throw ${generateRegId(FunSimCtx.RegId.ACC)};"
                    is IrOp.Throw.Error -> "throw Error(${op.msg});"
                    is IrOp.DeleteProp -> "delete ${generateRegId(op.obj)}[${generateRegId(op.prop)}];"
                    is IrOp.DefineGetterSetter -> "Object.defineProperty(${generateRegId(op.obj)}, ${generateRegId(op.prop)}, {get: ${generateRegId(op.getter)}, set: ${generateRegId(op.setter)}});"
                    is IrOp.AssignModuleVar -> {
                        val name = op.local.localName ?: "moduleSlot"
                        exports.add(op.local)
                        "$name = ${generateExpression(op.right)};"
                    }
                    is IrOp.Await -> "_acc_ = await ${generateExpression(op.source)};"
                    is IrOp.SpreadIntoArray -> "${generateRegId(op.arrReg)}.push(...${generateExpression(op.source)});"
                }
            }
        }
    }

    /**
     * 生成表达式的代码
     */
    private fun generateExpression(exp: IrOp.Expression): String {
        return when (exp) {
            is IrOp.BiExp.Add -> "${generateExpression(exp.l)} + ${generateExpression(exp.r)}"
            is IrOp.BiExp.Sub -> "${generateExpression(exp.l)} - ${generateExpression(exp.r)}"
            is IrOp.BiExp.Mul -> "${generateExpression(exp.l)} * ${generateExpression(exp.r)}"
            is IrOp.BiExp.Div -> "${generateExpression(exp.l)} / ${generateExpression(exp.r)}"
            is IrOp.BiExp.Mod -> "${generateExpression(exp.l)} % ${generateExpression(exp.r)}"
            is IrOp.BiExp.Eq -> "${generateExpression(exp.l)} == ${generateExpression(exp.r)}"
            is IrOp.BiExp.NEq -> "${generateExpression(exp.l)} != ${generateExpression(exp.r)}"
            is IrOp.BiExp.StrictEq -> "${generateExpression(exp.l)} === ${generateExpression(exp.r)}"
            is IrOp.BiExp.StrictNEq -> "${generateExpression(exp.l)} !== ${generateExpression(exp.r)}"
            is IrOp.BiExp.Less -> "${generateExpression(exp.l)} < ${generateExpression(exp.r)}"
            is IrOp.BiExp.LEq -> "${generateExpression(exp.l)} <= ${generateExpression(exp.r)}"
            is IrOp.BiExp.Ge -> "${generateExpression(exp.l)} > ${generateExpression(exp.r)}"
            is IrOp.BiExp.GEq -> "${generateExpression(exp.l)} >= ${generateExpression(exp.r)}"
            is IrOp.BiExp.And -> "${generateExpression(exp.l)} & ${generateExpression(exp.r)}"
            is IrOp.BiExp.Or -> "${generateExpression(exp.l)} | ${generateExpression(exp.r)}"
            is IrOp.BiExp.Xor -> "${generateExpression(exp.l)} ^ ${generateExpression(exp.r)}"
            is IrOp.BiExp.Shl -> "${generateExpression(exp.l)} << ${generateExpression(exp.r)}"
            is IrOp.BiExp.Shr -> "${generateExpression(exp.l)} >>> ${generateExpression(exp.r)}"
            is IrOp.BiExp.AShr -> "${generateExpression(exp.l)} >> ${generateExpression(exp.r)}"
            is IrOp.BiExp.Exp -> "${generateExpression(exp.l)} ** ${generateExpression(exp.r)}"
            is IrOp.BiExp.InstOf -> "${generateExpression(exp.l)} instanceof ${generateExpression(exp.r)}"
            is IrOp.BiExp.IsIn -> "${generateExpression(exp.l)} in ${generateExpression(exp.r)}"
            is IrOp.CallAcc -> "${exp.overrideThis?.let { generateRegId(it) } ?: "this"}.${generateRegId(FunSimCtx.RegId.ACC)}(${exp.args.joinToString { generateRegId(it) }})"
            is IrOp.CallWithTarget -> "${generateExpression(exp.target)}(${exp.args.joinToString { generateRegId(it) }})"
            is IrOp.DynamicImport -> "import(${generateRegId(exp.regId)})"
            is IrOp.JustImm -> generateJsValue(exp.value)
            is IrOp.LoadExternalModule -> "${exp.ext.also { imports.add(it) }.localName}"
            is IrOp.LoadLocalModuleVar -> "${exp.local.also { exports.add(it) }.localName ?: "moduleSlot"}"
            is IrOp.LoadReg -> generateRegId(exp.regId)
            is IrOp.NewClass -> "/* newClass */"
            is IrOp.NewInst -> "new ${generateRegId(exp.clazz)}(${exp.constructorArgs.joinToString { generateRegId(it) }})"
            is IrOp.ArrayLiteral -> generateArrayLiteral(exp)
            is IrOp.CopyRestArgs -> "Array.prototype.slice.call(arguments, ${exp.startIdx})"
            is IrOp.ObjField.Index -> "${generateExpression(exp.obj)}[${exp.index}]"
            is IrOp.ObjField.Name -> "${generateExpression(exp.obj)}.${exp.name}"
            is IrOp.ObjField.Value -> "${generateExpression(exp.obj)}[${generateRegId(exp.value)}]"
            is IrOp.UaExp.Dec -> "${generateExpression(exp.source)} - 1"
            is IrOp.GetModuleNamespace -> "import(${exp.ns.str})"
            is IrOp.UaExp.GetTemplateObject -> "/* getTemplateObject */"
            is IrOp.UaExp.Inc -> "${generateExpression(exp.source)} + 1"
            is IrOp.UaExp.IsFalse -> "${generateExpression(exp.source)} == false"
            is IrOp.UaExp.IsTrue -> "${generateExpression(exp.source)} == true"
            is IrOp.UaExp.Neg -> "-${generateExpression(exp.source)}"
            is IrOp.UaExp.Not -> "~${generateExpression(exp.source)}"
            is IrOp.UaExp.ToNumber -> "Number(${generateExpression(exp.source)})"
            is IrOp.UaExp.ToNumeric -> "Number(${generateExpression(exp.source)})"
            is IrOp.UaExp.TypeOf -> "typeof(${generateExpression(exp.source)})"
            is IrOp.UaExp.GetAsyncIterator -> "/* getAsyncIterator */ ${generateExpression(exp.source)}"
        }
    }

    /**
     * 生成寄存器 ID 的代码
     */
    private fun generateRegId(regId: FunSimCtx.RegId): String {
        if (regId.isReg()) {
            val v = regId.getRegV()
            val vRegs = asm.code.numVRegs
            return if (v < vRegs) "v$v" else when (val aIndex = v - vRegs) {
                0L -> "FunctionObject"
                1L -> "NewTarget"
                2L -> "this"
                else -> "arg${aIndex - 3}"
            }
        } else if (regId == FunSimCtx.RegId.GLOBAL) {
            return "AtkTsGlobal"
        } else if (regId == FunSimCtx.RegId.THIS) {
            return "this"
        } else if (regId.value and FunSimCtx.RegId.MASK == FunSimCtx.RegId.MASK_LEX) {
            val (lvl, slot) = regId.lexLvlSlot() ?: return regId.toJS()
            return resolveLexName(currentLexEnvStack, lvl, slot) ?: "__lex${lvl}_${slot}__"
        } else return regId.toJS()
    }

    /**
     * 生成数组字面量表达式
     */
    private fun generateArrayLiteral(arrayLiteral: IrOp.ArrayLiteral): String {
        val elements = arrayLiteral.elements
        return if (elements.size > MAX_LITERAL_ARRAY_SIZE) {
            val preview = elements.take(MAX_LITERAL_ARRAY_SIZE).joinToString(",") { generateArrayElement(it) }
            "[$preview,/* ... ${elements.size - MAX_LITERAL_ARRAY_SIZE} more items */]"
        } else {
            elements.joinToString(",", "[", "]") { generateArrayElement(it) }
        }
    }

    private fun generateArrayElement(element: IrOp.ArrayElement): String {
        return when (element) {
            is IrOp.ArrayElement.Expr -> generateExpression(element.expr)
            is IrOp.ArrayElement.Spread -> "...${generateExpression(element.expr)}"
        }
    }

    /**
     * 生成 JS 值的代码
     * 对超大 ArrInst / ObjInst 做截断，避免单行表达式突破 JVM 字符串上限
     */
    private fun generateJsValue(jsValue: me.yricky.oh.abcd.decompiler.behaviour.JSValue): String {
        return when (jsValue) {
            is me.yricky.oh.abcd.decompiler.behaviour.JSValue.ArrInst -> {
                if (jsValue.content.size > MAX_LITERAL_ARRAY_SIZE) {
                    val preview = jsValue.content.take(MAX_LITERAL_ARRAY_SIZE)
                        .joinToString(",") { generateJsValue(it) }
                    "[$preview,/* ... ${jsValue.content.size - MAX_LITERAL_ARRAY_SIZE} more items */]"
                } else {
                    jsValue.content.joinToString(",", "[", "]") { generateJsValue(it) }
                }
            }
            is me.yricky.oh.abcd.decompiler.behaviour.JSValue.ClassObj -> "/* classObj */"
            is me.yricky.oh.abcd.decompiler.behaviour.JSValue.Error -> "Error(...)"
            me.yricky.oh.abcd.decompiler.behaviour.JSValue.False -> "false"
            is me.yricky.oh.abcd.decompiler.behaviour.JSValue.Function -> {
                val m = jsValue.method
                if (m is AbcMethod) "function ${decodeMethodName(m)}()" else "function ${m.name}()"
            }
            me.yricky.oh.abcd.decompiler.behaviour.JSValue.Hole -> "undefined /* hole */"
            me.yricky.oh.abcd.decompiler.behaviour.JSValue.Infinity -> "Infinity"
            me.yricky.oh.abcd.decompiler.behaviour.JSValue.Nan -> "NaN"
            me.yricky.oh.abcd.decompiler.behaviour.JSValue.Null -> "null"
            is me.yricky.oh.abcd.decompiler.behaviour.JSValue.Number -> jsValue.value.toString()
            is me.yricky.oh.abcd.decompiler.behaviour.JSValue.BigInt -> "${jsValue.value}n"
            is me.yricky.oh.abcd.decompiler.behaviour.JSValue.ObjInst -> {
                if (jsValue.content.size > MAX_LITERAL_ARRAY_SIZE) {
                    val preview = jsValue.content.entries.take(MAX_LITERAL_ARRAY_SIZE)
                        .joinToString(", ") { "${it.key}:${generateJsValue(it.value)}" }
                    "{$preview,/* ... ${jsValue.content.size - MAX_LITERAL_ARRAY_SIZE} more keys */}"
                } else if (jsValue.content.isEmpty()) {
                    "{}"
                } else {
                    jsValue.content.asSequence().joinToString(", ", "{", "}") {
                        "${it.key}:${generateJsValue(it.value)}"
                    }
                }
            }
            is me.yricky.oh.abcd.decompiler.behaviour.JSValue.Str -> "\"${jsValue.value}\""
            me.yricky.oh.abcd.decompiler.behaviour.JSValue.Symbol.Iterator -> "Symbol.iterator"
            me.yricky.oh.abcd.decompiler.behaviour.JSValue.Symbol.SymbolObj -> "Symbol"
            me.yricky.oh.abcd.decompiler.behaviour.JSValue.True -> "true"
            me.yricky.oh.abcd.decompiler.behaviour.JSValue.Undefined -> "undefined"
        }
    }
}
