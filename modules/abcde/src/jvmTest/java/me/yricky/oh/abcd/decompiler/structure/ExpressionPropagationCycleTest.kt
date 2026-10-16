package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.decompiler.behaviour.FunSimCtx
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.decompiler.behaviour.JSValue
import org.junit.Assert.*
import org.junit.Test

/**
 * ExpressionPropagationPass 自引用映射循环内联防护测试
 *
 * 背景：run() 将内联结果存回 exprMap，可能产生自引用映射
 * （典型如 async 状态机中的 `_acc_ = _acc_ + _acc_` 累加器复用）。
 * 修复前 inlineExpression 沿 exprMap 环把表达式树按深度上限 50 指数展开
 * （分支因子 2 时约 2^25 个节点），每个后续引用点都会重复爆炸，
 * 导致 decompile_method 在普通 async 方法上卡死直到 MCP 超时。
 *
 * 注意：IrOp 未实现结构 equals，断言需按结构逐层解构。
 */
class ExpressionPropagationCycleTest {

    private fun num(value: Int) = IrOp.JustImm(JSValue.Number(value))

    @Test(timeout = 60_000)
    fun testSelfReferentialAccInlineTerminates() {
        // op1: ACC = ACC + ACC  →  exprMap[ACC] = Add(LoadReg(ACC), LoadReg(ACC))（自引用）
        // op2: v1 = ACC          →  修复前内联沿环深度展开（左子树仍是 Add），修复后两个 ACC 引用保持为符号
        val ops = listOf<IrOp>(
            IrOp.AssignReg(FunSimCtx.RegId.ACC, IrOp.BiExp.Add(regOfACC(), regOfACC())),
            IrOp.AssignReg(FunSimCtx.RegId(1), IrOp.LoadReg(FunSimCtx.RegId.ACC)),
        )
        val result = ExpressionPropagationPass.run(ops)

        val right = (result[1] as IrOp.AssignReg).right
        val add = assertAdd(right)
        assertLoadReg(add.l, FunSimCtx.RegId.ACC)
        assertLoadReg(add.r, FunSimCtx.RegId.ACC)
    }

    @Test(timeout = 60_000)
    fun testPartialCycleKeepsAcyclicParts() {
        // ACC = ACC + v1; v1 = 5; v2 = ACC
        // 修复后：v2 内联为 Add(LoadReg(ACC), 5)，环上引用保留符号，非环部分正常内联
        val ops = listOf<IrOp>(
            IrOp.AssignReg(FunSimCtx.RegId.ACC, IrOp.BiExp.Add(regOfACC(), IrOp.LoadReg(FunSimCtx.RegId(2)))),
            IrOp.AssignReg(FunSimCtx.RegId(2), num(5)),
            IrOp.AssignReg(FunSimCtx.RegId(3), IrOp.LoadReg(FunSimCtx.RegId.ACC)),
        )
        val result = ExpressionPropagationPass.run(ops)

        val right = (result[2] as IrOp.AssignReg).right
        val add = assertAdd(right)
        assertLoadReg(add.l, FunSimCtx.RegId.ACC)
        assertNumber(5, add.r)
    }

    @Test
    fun testAcyclicChainStillInlines() {
        // 无环链式内联必须不受影响：v1 = 42; v2 = v1 + 1; v3 = v2 → v3 = 42 + 1
        val ops = listOf<IrOp>(
            IrOp.AssignReg(FunSimCtx.RegId(1), num(42)),
            IrOp.AssignReg(FunSimCtx.RegId(2), IrOp.BiExp.Add(IrOp.LoadReg(FunSimCtx.RegId(1)), num(1))),
            IrOp.AssignReg(FunSimCtx.RegId(3), IrOp.LoadReg(FunSimCtx.RegId(2))),
        )
        val result = ExpressionPropagationPass.run(ops)

        val right = (result[2] as IrOp.AssignReg).right
        val add = assertAdd(right)
        assertNumber(42, add.l)
        assertNumber(1, add.r)
    }

    private fun assertAdd(expr: IrOp.Expression): IrOp.BiExp.Add {
        assertTrue("期望 Add 表达式，实际为 ${expr.javaClass.simpleName}", expr is IrOp.BiExp.Add)
        return expr as IrOp.BiExp.Add
    }

    private fun assertLoadReg(expr: IrOp.Expression, regId: FunSimCtx.RegId) {
        assertTrue("期望 LoadReg，实际为 ${expr.javaClass.simpleName}", expr is IrOp.LoadReg)
        assertEquals(regId, (expr as IrOp.LoadReg).regId)
    }

    private fun assertNumber(value: Int, expr: IrOp.Expression) {
        assertTrue("期望数字常量，实际为 ${expr.javaClass.simpleName}", expr is IrOp.JustImm)
        assertTrue((expr as IrOp.JustImm).value is JSValue.Number)
        assertEquals(value.toDouble(), (expr.value as JSValue.Number).value.toDouble(), 0.0)
    }

    private fun regOfACC() = IrOp.LoadReg(FunSimCtx.RegId.ACC)
}
