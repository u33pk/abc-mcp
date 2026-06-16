package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.decompiler.behaviour.FunSimCtx
import me.yricky.oh.abcd.decompiler.behaviour.IrOp

/**
 * 数组展开合并 Pass
 *
 * `starrayspread v0, v1` 的语义：将 ACC 中的可迭代对象展开到 v0 数组中。
 * 典型 TS 模式：
 *
 *     _acc_ = [];
 *     v0 = _acc_;
 *     _acc_ = src;
 *     starrayspread v0, idx
 *
 * 合并为：
 *
 *     v0 = [...src]
 *
 * 若无法找到可追踪的数组字面量赋值，或源表达式包含副作用不适合复制，
 * 则保留 `SpreadIntoArray` 节点，由代码生成器降级为 `arr.push(...src);`。
 */
object ArraySpreadMergingPass : OptimizationPass {

    override fun run(ops: List<IrOp>): List<IrOp> {
        val result = mutableListOf<IrOp>()
        // 记录每个寄存器当前持有的 ArrayLiteral（必须是可直接修改的表达式）
        val arrayLiteralMap = mutableMapOf<FunSimCtx.RegId, IrOp.ArrayLiteral>()
        // 记录每个寄存器对应的 ArrayLiteral 是在 result 的哪个下标处引入的
        val arrayAssignIndex = mutableMapOf<FunSimCtx.RegId, Int>()
        // 记录当前 ACC 中的表达式，用于在 starrayspread 时捕获源对象
        var accExpr: IrOp.Expression = IrOp.LoadReg(FunSimCtx.RegId.ACC)

        fun invalidateForReg(reg: FunSimCtx.RegId) {
            arrayLiteralMap.remove(reg)
            arrayAssignIndex.remove(reg)
        }

        fun invalidateAll() {
            arrayLiteralMap.clear()
            arrayAssignIndex.clear()
        }

        /**
         * 判断表达式是否安全复制到 ArrayLiteral 中。
         * 只允许无副作用的寄存器读取、字面量、以及同样由字面量组成的表达式。
         */
        fun isSafeToDuplicate(expr: IrOp.Expression): Boolean = when (expr) {
            is IrOp.LoadReg -> true
            is IrOp.JustImm -> true
            is IrOp.ArrayLiteral -> expr.elements.all {
                when (it) {
                    is IrOp.ArrayElement.Expr -> isSafeToDuplicate(it.expr)
                    is IrOp.ArrayElement.Spread -> isSafeToDuplicate(it.expr)
                }
            }
            is IrOp.ObjField.Name -> isSafeToDuplicate(expr.obj)
            is IrOp.ObjField.Index -> isSafeToDuplicate(expr.obj)
            else -> false
        }

        /**
         * 将可能引用 ACC 的源表达式中的 LoadReg(ACC) 替换为当前 accExpr。
         */
        fun resolveAcc(expr: IrOp.Expression): IrOp.Expression = when (expr) {
            is IrOp.LoadReg -> if (expr.regId == FunSimCtx.RegId.ACC) accExpr else expr
            is IrOp.ArrayLiteral -> IrOp.ArrayLiteral(expr.elements.map {
                when (it) {
                    is IrOp.ArrayElement.Expr -> IrOp.ArrayElement.Expr(resolveAcc(it.expr))
                    is IrOp.ArrayElement.Spread -> IrOp.ArrayElement.Spread(resolveAcc(it.expr))
                }
            })
            is IrOp.ObjField.Name -> IrOp.ObjField.Name(resolveAcc(expr.obj), expr.name)
            is IrOp.ObjField.Index -> IrOp.ObjField.Index(resolveAcc(expr.obj), expr.index)
            else -> expr
        }

        for (op in ops) {
            when (op) {
                is IrOp.AssignReg -> {
                    val right = op.right
                    if (op.left == FunSimCtx.RegId.ACC) {
                        accExpr = right
                    }
                    val literal = when {
                        right is IrOp.ArrayLiteral -> right
                        right is IrOp.LoadReg && right.regId == FunSimCtx.RegId.ACC && accExpr is IrOp.ArrayLiteral ->
                            accExpr as IrOp.ArrayLiteral
                        right is IrOp.LoadReg && arrayLiteralMap.containsKey(right.regId) ->
                            arrayLiteralMap[right.regId]!!
                        else -> null
                    }
                    if (literal != null) {
                        arrayLiteralMap[op.left] = literal
                        arrayAssignIndex[op.left] = result.size
                    } else {
                        invalidateForReg(op.left)
                    }
                    result.add(op)
                }
                is IrOp.AssignObj -> {
                    // 对象字段写入可能修改任意对象，包括数组；保守清空
                    invalidateAll()
                    // ACC 内容未知
                    accExpr = IrOp.LoadReg(FunSimCtx.RegId.ACC)
                    result.add(op)
                }
                is IrOp.SpreadIntoArray -> {
                    val source = resolveAcc(op.source)
                    val arrLiteral = arrayLiteralMap[op.arrReg]
                    val assignIndex = arrayAssignIndex[op.arrReg]
                    if (arrLiteral != null && assignIndex != null && isSafeToDuplicate(source)) {
                        val newElements = arrLiteral.elements + IrOp.ArrayElement.Spread(source)
                        val newLiteral = IrOp.ArrayLiteral(newElements)
                        val oldAssign = result[assignIndex] as IrOp.AssignReg
                        result[assignIndex] = IrOp.AssignReg(oldAssign.left, newLiteral)
                        arrayLiteralMap[op.arrReg] = newLiteral
                        // assignIndex 保持不变
                        // 若源是 ACC，且前一条恰好是 _acc_ = source，则该 ACC 赋值通常已变为死代码，
                        // 由 DeadCodeEliminationPass 处理；这里不再保留 SpreadIntoArray。
                    } else {
                        // 无法追踪或源不安全：保留 fallback
                        result.add(IrOp.SpreadIntoArray(op.arrReg, source))
                    }
                }
                is IrOp.Statement -> {
                    for (reg in op.effected()) {
                        invalidateForReg(reg)
                        if (reg == FunSimCtx.RegId.ACC) {
                            accExpr = IrOp.LoadReg(FunSimCtx.RegId.ACC)
                        }
                    }
                    result.add(op)
                }
                is IrOp.AssignModuleVar -> {
                    // 模块变量赋值不影响数组寄存器；右值读取 ACC 但不改变 ACC
                    result.add(op)
                }
                else -> {
                    result.add(op)
                }
            }
        }

        return result
    }
}
