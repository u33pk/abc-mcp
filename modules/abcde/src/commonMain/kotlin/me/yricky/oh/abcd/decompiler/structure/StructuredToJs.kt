package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.cfm.AbcMethod
import me.yricky.oh.abcd.cfm.argsStr
import me.yricky.oh.abcd.decompiler.CodeSegment
import me.yricky.oh.abcd.decompiler.behaviour.FunSimCtx
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.decompiler.structure.statement.DoWhileStatement
import me.yricky.oh.abcd.decompiler.structure.statement.IfStatement
import me.yricky.oh.abcd.decompiler.structure.statement.WhileStatement
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.abcd.isa.asmName
import me.yricky.oh.abcd.literal.ModuleLiteralArray

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
class StructuredToJs(val asm: Asm) {
    companion object {
        /** 单个方法反编译输出的最大字符数（10 MB） */
        const val MAX_TOTAL_OUTPUT_SIZE = 10 * 1024 * 1024
        /** 字面量数组/对象最多完整展示的元素/键值对数量 */
        const val MAX_LITERAL_ARRAY_SIZE = 1000
    }

    private val imports = mutableListOf<ModuleLiteralArray.RegularImport>()
    private val nsImports = mutableListOf<ModuleLiteralArray.NamespaceImport>()
    private var outputSize = 0

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
            val restIndex = asm.irOpList.filterIsInstance<IrOp.CopyRestArgs>().firstOrNull()?.startIdx ?: -1
            sb.safeAppend("function $methodName${asm.code.method.argsStr(restIndex)} {\n")

            // 生成函数体（直接写入 sb，确保超出预算时保留尽可能多的部分输出）
            generateRegion(region, sb, 1)

            // 生成函数尾
            sb.safeAppend("}")

            // 添加导入语句
            val importStr = imports.joinToString(separator = ";\n") { it.toString() }
            val nsImportStr = nsImports.joinToString(separator = ";\n") { it.toString() }

            "$importStr\n$nsImportStr\n\n$sb".trim()
        } catch (e: OutputTooLargeException) {
            // 把当前已生成的部分输出附加到异常中，供上层拼接摘要后返回给 LLM
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
                }
                is WhileStatement -> {
                    if (statement.condition == null) {
                        out.safeAppend("${indentStr}while (true) {\n")
                    } else {
                        val condStr = generateCondition(statement.condition)
                        out.safeAppend("${indentStr}while ($condStr) {\n")
                    }
                    generateRegion(statement.body, out, indent + 1, depth + 1)
                    out.safeAppend("${indentStr}}\n")
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
     */
    private fun generateLinearBlock(block: CodeSegment.BasicBlock, out: StringBuilder, indent: Int) {
        val indentStr = "    ".repeat(indent)

        when (block) {
            is CodeSegment.Linear -> {
                // 收集所有指令并优化
                val ops = block.map { it.irOp }.toList()
                val liveOut = mutableSetOf<FunSimCtx.RegId>()
                liveOut.add(FunSimCtx.RegId.ACC)
                val optimizedOps = IrOpOptimizer.optimizeList(ops, liveOut)

                for (irOp in optimizedOps) {
                    out.safeAppend("$indentStr${generateIrOp(irOp)}\n")
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
            IrOp.NOP -> "/* nop */"
            is IrOp.NewLex -> "/* newLex(${op.size}) */"
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
            is IrOp.LoadReg -> generateRegId(exp.regId)
            is IrOp.NewClass -> "/* newClass */"
            is IrOp.NewInst -> "new ${generateRegId(exp.clazz)}(${exp.constructorArgs.joinToString { generateRegId(it) }})"
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
        } else return regId.toJS()
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
