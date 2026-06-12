package me.yricky.oh.abcd.decompiler.structure

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
 * 基于结构化分析的代码生成器
 * 将 Region 树转换为 JavaScript/TypeScript 代码
 */
class StructuredToJs(val asm: Asm) {
    private val imports = mutableListOf<ModuleLiteralArray.RegularImport>()
    private val nsImports = mutableListOf<ModuleLiteralArray.NamespaceImport>()

    /**
     * 生成完整的函数代码
     */
    fun generate(region: Region): String {
        val sb = StringBuilder()
        
        // 生成函数头
        sb.append("function ${asm.code.method.name}${asm.code.method.argsStr()} {\n")
        
        // 生成函数体
        sb.append(generateRegion(region, 1))
        
        // 生成函数尾
        sb.append("}")
        
        // 添加导入语句
        val importStr = imports.joinToString(separator = ";\n") { it.toString() }
        val nsImportStr = nsImports.joinToString(separator = ";\n") { it.toString() }
        
        return "$importStr\n$nsImportStr\n\n$sb".trim()
    }

    /**
     * 生成区域代码
     */
    private fun generateRegion(region: Region, indent: Int, depth: Int = 0): String {
        if (depth > 100) return "${"    ".repeat(indent)}// [depth limit reached]\n"
        val sb = StringBuilder()
        val indentStr = "    ".repeat(indent)
        
        for (statement in region.statements) {
            when (statement) {
                is IfStatement -> {
                    val condStr = generateCondition(statement.condition)
                    sb.append("${indentStr}if ($condStr) {\n")
                    if (statement.thenBranch != null) {
                        sb.append(generateRegion(statement.thenBranch, indent + 1, depth + 1))
                    }
                    if (statement.elseBranch != null) {
                        sb.append("${indentStr}} else {\n")
                        sb.append(generateRegion(statement.elseBranch, indent + 1, depth + 1))
                    }
                    sb.append("${indentStr}}\n")
                }
                is WhileStatement -> {
                    if (statement.condition == null) {
                        sb.append("${indentStr}while (true) {\n")
                    } else {
                        val condStr = generateCondition(statement.condition)
                        sb.append("${indentStr}while ($condStr) {\n")
                    }
                    sb.append(generateRegion(statement.body, indent + 1, depth + 1))
                    sb.append("${indentStr}}\n")
                }
                is DoWhileStatement -> {
                    sb.append("${indentStr}do {\n")
                    sb.append(generateRegion(statement.body, indent + 1, depth + 1))
                    sb.append("${indentStr}} while (/* condition */);\n")
                }
                is LinearStatement -> {
                    sb.append(generateLinearBlock(statement.block, indent))
                }
                else -> {
                    sb.append("${indentStr}// unknown statement type\n")
                }
            }
        }
        
        return sb.toString()
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
     * 生成线性基本块的代码
     */
    private fun generateLinearBlock(block: CodeSegment.BasicBlock, indent: Int): String {
        val sb = StringBuilder()
        val indentStr = "    ".repeat(indent)
        
        when (block) {
            is CodeSegment.Linear -> {
                // 收集所有指令并优化
                val ops = block.map { it.irOp }.toList()
                val liveOut = mutableSetOf<FunSimCtx.RegId>()
                liveOut.add(FunSimCtx.RegId.ACC)
                val optimizedOps = IrOpOptimizer.optimizeList(ops, liveOut)
                
                // 追踪 ACC 的来源，用于合并函数调用
                var accSource: IrOp.Expression? = null
                
                for (irOp in optimizedOps) {
                    when {
                        // _acc_ = obj.method; 模式
                        irOp is IrOp.AssignReg && irOp.left == FunSimCtx.RegId.ACC -> {
                            accSource = irOp.right
                            sb.append("$indentStr${generateIrOp(irOp)}\n")
                        }
                        // _acc_ = this._acc_(args); 模式，且 ACC 来源是 ObjField.Name
                        irOp is IrOp.AssignReg && irOp.left == FunSimCtx.RegId.ACC &&
                        irOp.right is IrOp.CallAcc && accSource is IrOp.ObjField.Name -> {
                            val call = irOp.right as IrOp.CallAcc
                            val source = accSource as IrOp.ObjField.Name
                            // 合并为 obj.method(args)
                            val objStr = generateRegId(source.obj)
                            val argsStr = call.args.joinToString { generateRegId(it) }
                            sb.append("$indentStr${generateRegId(FunSimCtx.RegId.ACC)} = $objStr.${source.name}($argsStr);\n")
                            accSource = null
                        }
                        else -> {
                            sb.append("$indentStr${generateIrOp(irOp)}\n")
                            // 非 ACC 赋值，清除来源追踪
                            if (irOp !is IrOp.AssignReg || irOp.left != FunSimCtx.RegId.ACC) {
                                // 不清除，因为可能还有后续的 CallAcc
                            }
                        }
                    }
                }
            }
            is CodeSegment.Return -> {
                sb.append("$indentStr${generateIrOp(block.item.irOp)}\n")
            }
            is CodeSegment.JumpMark -> {
                // 无条件跳转，通常不需要生成代码
            }
            is CodeSegment.InsCondition -> {
                // 条件跳转，通常由 IfStatement 处理
            }
        }
        
        return sb.toString()
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
            is IrOp.DynamicImport -> "import(${generateRegId(exp.regId)})"
            is IrOp.JustImm -> generateJsValue(exp.value)
            is IrOp.LoadExternalModule -> "${exp.ext.also { imports.add(it) }.localName}"
            is IrOp.LoadReg -> generateRegId(exp.regId)
            is IrOp.NewClass -> "/* newClass */"
            is IrOp.NewInst -> "new ${generateRegId(exp.clazz)}(${exp.constructorArgs.joinToString { generateRegId(it) }})"
            is IrOp.ObjField.Index -> "${generateRegId(exp.obj)}[${exp.index}]"
            is IrOp.ObjField.Name -> "${generateRegId(exp.obj)}.${exp.name}"
            is IrOp.ObjField.Value -> "${generateRegId(exp.obj)}[${generateRegId(exp.value)}]"
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
     */
    private fun generateJsValue(jsValue: me.yricky.oh.abcd.decompiler.behaviour.JSValue): String {
        return when (jsValue) {
            is me.yricky.oh.abcd.decompiler.behaviour.JSValue.ArrInst -> jsValue.content.joinToString(",", "[", "]") { generateJsValue(it) }
            is me.yricky.oh.abcd.decompiler.behaviour.JSValue.ClassObj -> "/* classObj */"
            is me.yricky.oh.abcd.decompiler.behaviour.JSValue.Error -> "Error(...)"
            me.yricky.oh.abcd.decompiler.behaviour.JSValue.False -> "false"
            is me.yricky.oh.abcd.decompiler.behaviour.JSValue.Function -> "function ${jsValue.method.name}()"
            me.yricky.oh.abcd.decompiler.behaviour.JSValue.Hole -> "undefined /* hole */"
            me.yricky.oh.abcd.decompiler.behaviour.JSValue.Infinity -> "Infinity"
            me.yricky.oh.abcd.decompiler.behaviour.JSValue.Nan -> "NaN"
            me.yricky.oh.abcd.decompiler.behaviour.JSValue.Null -> "null"
            is me.yricky.oh.abcd.decompiler.behaviour.JSValue.Number -> jsValue.value.toString()
            is me.yricky.oh.abcd.decompiler.behaviour.JSValue.BigInt -> "${jsValue.value}n"
            is me.yricky.oh.abcd.decompiler.behaviour.JSValue.ObjInst -> if (jsValue.content.isEmpty()) "{}" else jsValue.content.asSequence().joinToString(", ", "{", "}") {
                "${it.key}:${generateJsValue(it.value)}"
            }
            is me.yricky.oh.abcd.decompiler.behaviour.JSValue.Str -> "\"${jsValue.value}\""
            me.yricky.oh.abcd.decompiler.behaviour.JSValue.Symbol.Iterator -> "Symbol.iterator"
            me.yricky.oh.abcd.decompiler.behaviour.JSValue.Symbol.SymbolObj -> "Symbol"
            me.yricky.oh.abcd.decompiler.behaviour.JSValue.True -> "true"
            me.yricky.oh.abcd.decompiler.behaviour.JSValue.Undefined -> "undefined"
        }
    }
}
