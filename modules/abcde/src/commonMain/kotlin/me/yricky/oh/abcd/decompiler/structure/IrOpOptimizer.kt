package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.decompiler.behaviour.FunSimCtx
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.decompiler.behaviour.assignLeftAcc
import me.yricky.oh.abcd.decompiler.behaviour.assignLeftContainsAcc
import me.yricky.oh.abcd.decompiler.behaviour.assignRightAcc
import me.yricky.oh.abcd.decompiler.behaviour.assignRightContainsAcc

/**
 * IR 指令优化器
 * 使用 LLVM 风格的 Pass 架构
 */
object IrOpOptimizer {
    
    /**
     * 优化 Pass 列表
     * 按顺序执行，迭代直到不动点
     */
    private val passes = listOf(
        ExpressionPropagationPass,    // 表达式传播（最先执行）
        AlgebraicSimplificationPass,  // 代数化简
        CopyPropagationPass,          // 拷贝传播
        DeadCodeEliminationPass,      // 死代码消除
        AccCopyPropagationPass        // ACC 拷贝传播（保留原有逻辑）
    )
    
    /**
     * 优化指令序列
     */
    fun optimize(linear: Sequence<IrOp>): Sequence<IrOp> {
        return sequence {
            val ops = linear.toList()
            val optimized = optimizeList(ops)
            yieldAll(optimized)
        }
    }
    
    /**
     * 优化指令列表
     * 迭代执行所有 Pass 直到不动点
     * @param liveOut 后续会使用的寄存器集合（如 Return 会使用 ACC）
     */
    fun optimizeList(ops: List<IrOp>, liveOut: Set<FunSimCtx.RegId> = emptySet()): List<IrOp> {
        var result = ops
        var changed: Boolean
        var iterations = 0
        val maxIterations = 10
        
        do {
            changed = false
            for (pass in passes) {
                val newResult = if (pass is DeadCodeEliminationPass) {
                    pass.run(result, liveOut)
                } else {
                    pass.run(result)
                }
                if (newResult.size != result.size || newResult != result) {
                    changed = true
                    result = newResult
                }
            }
            iterations++
        } while (changed && iterations < maxIterations)
        
        return result
    }
}

/**
 * ACC 拷贝传播 Pass
 * 保留原有的 ACC 拷贝传播逻辑
 */
object AccCopyPropagationPass : OptimizationPass {
    override fun run(ops: List<IrOp>): List<IrOp> {
        val result = mutableListOf<IrOp>()
        var i = 0
        
        while (i < ops.size) {
            // 尝试 2 条指令的优化：_acc_ = xxx; yyy = _acc_; -> yyy = xxx;
            if (i + 1 < ops.size) {
                val op0 = ops[i]
                val op1 = ops[i + 1]
                val cond0LeftAcc = op0 is IrOp.Assign && op0.leftReg == FunSimCtx.RegId.ACC && 
                    !(op0.right.effected().contains(FunSimCtx.RegId.ACC) || op0.right.read().contains(FunSimCtx.RegId.ACC))
                val cond1RightAcc = op1 is IrOp.Assign && (op1.right as? IrOp.LoadReg)?.regId == FunSimCtx.RegId.ACC && 
                    op1.leftReg != FunSimCtx.RegId.ACC
                
                if (cond0LeftAcc && cond1RightAcc) {
                    val firstOp = op0 as IrOp.Assign
                    val secondOp = op1 as IrOp.Assign
                    result.add(secondOp.replaceRight(firstOp.right))
                    i += 2
                    continue
                }
            }
            
            // 尝试 3 条指令的优化
            if (i + 2 < ops.size) {
                val op0 = ops[i]
                val op1 = ops[i + 1]
                val op2 = ops[i + 2]
                
                val cond0 = op0 is IrOp.Assign && op0.leftReg == FunSimCtx.RegId.ACC && 
                    !(op0.right.effected().contains(FunSimCtx.RegId.ACC) || op0.right.read().contains(FunSimCtx.RegId.ACC))
                val cond1 = op1 is IrOp.Assign && (op1.right as? IrOp.LoadReg)?.regId == FunSimCtx.RegId.ACC && 
                    op1.leftReg != FunSimCtx.RegId.ACC
                val cond2 = op2 is IrOp.Assign && op2.leftReg == FunSimCtx.RegId.ACC && 
                    !(op2.right.effected().contains(FunSimCtx.RegId.ACC) || op2.right.read().contains(FunSimCtx.RegId.ACC))
                
                if (cond0 && cond1 && cond2) {
                    val firstOp = op0 as IrOp.Assign
                    val secondOp = op1 as IrOp.Assign
                    result.add(secondOp.replaceRight(firstOp.right))
                    result.add(op2)
                    i += 3
                    continue
                }
            }
            
            result.add(ops[i])
            i++
        }
        
        return result
    }
}
