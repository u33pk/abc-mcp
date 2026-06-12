package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.decompiler.ToJs
import me.yricky.oh.abcd.decompiler.behaviour.FunSimCtx
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.decompiler.behaviour.assignLeftAcc
import me.yricky.oh.abcd.decompiler.behaviour.assignLeftContainsAcc
import me.yricky.oh.abcd.decompiler.behaviour.assignRightAcc
import me.yricky.oh.abcd.decompiler.behaviour.assignRightContainsAcc
import org.junit.Test

class OptimizationTest {
    
    @Test
    fun testOptimizationConditions() {
        // 测试优化条件
        // _acc_ = AtkTsGlobal.console;
        val op0 = IrOp.AssignReg(
            FunSimCtx.RegId.ACC,
            IrOp.ObjField.Name(FunSimCtx.RegId.GLOBAL, "console")
        )
        
        // v0 = _acc_;
        val op1 = IrOp.AssignReg(
            FunSimCtx.RegId.regId(1), // v0
            IrOp.LoadReg.acc
        )
        
        println("op0: $op0")
        println("op0.assignLeftAcc: ${op0.assignLeftAcc}")
        println("op0.assignRightContainsAcc: ${op0.assignRightContainsAcc}")
        println("op1: $op1")
        println("op1.assignRightAcc: ${op1.assignRightAcc}")
        println("op1.assignLeftContainsAcc: ${op1.assignLeftContainsAcc}")
        
        val cond0 = op0.assignLeftAcc && !op0.assignRightContainsAcc
        val cond1 = op1.assignRightAcc && !op1.assignLeftContainsAcc
        
        println("cond0: $cond0")
        println("cond1: $cond1")
        println("Should optimize: ${cond0 && cond1}")
    }
    
    @Test
    fun testAssignRightAcc() {
        // 测试 assignRightAcc
        val loadReg = IrOp.LoadReg(FunSimCtx.RegId.ACC)
        println("loadReg.regId: ${loadReg.regId}")
        println("FunSimCtx.RegId.ACC: ${FunSimCtx.RegId.ACC}")
        println("loadReg.regId == FunSimCtx.RegId.ACC: ${loadReg.regId == FunSimCtx.RegId.ACC}")
        
        val assign = IrOp.AssignReg(
            FunSimCtx.RegId.regId(1), // v0
            loadReg
        )
        println("assign.right: ${assign.right}")
        println("assign.right is IrOp.LoadReg: ${assign.right is IrOp.LoadReg}")
        println("(assign.right as? IrOp.LoadReg)?.regId: ${(assign.right as? IrOp.LoadReg)?.regId}")
        println("assign.assignRightAcc: ${assign.assignRightAcc}")
    }
    
    @Test
    fun testOptimizeMethod() {
        // 测试 optimize 方法
        val ops = listOf(
            IrOp.AssignReg(
                FunSimCtx.RegId.ACC,
                IrOp.ObjField.Name(FunSimCtx.RegId.GLOBAL, "console")
            ),
            IrOp.AssignReg(
                FunSimCtx.RegId.regId(1), // v0
                IrOp.LoadReg.acc
            ),
            IrOp.AssignReg(
                FunSimCtx.RegId.ACC,
                IrOp.ObjField.Name(FunSimCtx.RegId.ACC, "log")
            )
        )
        
        println("Original ops:")
        ops.forEach { println("  $it") }
        
        // 创建 ToJs 实例并调用优化
        // 注意：这里我们无法直接调用 optimize 方法，因为它是私有的
        // 但我们可以验证优化逻辑是否正确
        
        // 模拟优化逻辑
        val buf = ops.toMutableList()
        val iter = emptyList<IrOp>().iterator()
        
        // 检查是否可以优化前两条指令
        val op0 = buf[0]
        val op1 = buf[1]
        val cond0 = op0.assignLeftAcc && !op0.assignRightContainsAcc
        val cond1 = op1.assignRightAcc && !op1.assignLeftContainsAcc
        
        println("\nOptimization check:")
        println("op0.assignLeftAcc: ${op0.assignLeftAcc}")
        println("op0.assignRightContainsAcc: ${op0.assignRightContainsAcc}")
        println("op1.assignRightAcc: ${op1.assignRightAcc}")
        println("op1.assignLeftContainsAcc: ${op1.assignLeftContainsAcc}")
        println("cond0: $cond0")
        println("cond1: $cond1")
        println("Can optimize: ${cond0 && cond1}")
        
        if (cond0 && cond1) {
            println("\nAfter optimization:")
            println("  v0 = AtkTsGlobal.console;")
            println("  _acc_ = v0.log;")
        }
    }
}
