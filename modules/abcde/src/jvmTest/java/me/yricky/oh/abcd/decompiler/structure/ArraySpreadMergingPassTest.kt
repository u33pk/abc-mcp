package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.decompiler.behaviour.FunSimCtx
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.decompiler.behaviour.JSValue
import org.junit.Assert.*
import org.junit.Test

/**
 * [ArraySpreadMergingPass] 的单元测试
 */
class ArraySpreadMergingPassTest {

    private val v0 = FunSimCtx.RegId.regId(10)
    private val v1 = FunSimCtx.RegId.regId(11)
    private val v2 = FunSimCtx.RegId.regId(12)

    @Test
    fun `merge empty array with two spreads`() {
        val ops = listOf(
            IrOp.AssignReg(FunSimCtx.RegId.ACC, IrOp.ArrayLiteral(emptyList())),
            IrOp.AssignReg(v0, IrOp.LoadReg.acc),
            IrOp.AssignReg(FunSimCtx.RegId.ACC, IrOp.LoadReg(v1)),
            IrOp.SpreadIntoArray(v0, IrOp.LoadReg.acc),
            IrOp.AssignReg(FunSimCtx.RegId.ACC, IrOp.LoadReg(v2)),
            IrOp.SpreadIntoArray(v0, IrOp.LoadReg.acc)
        )

        val result = ArraySpreadMergingPass.run(ops)

        val assign = result.single { it is IrOp.AssignReg && it.left == v0 } as IrOp.AssignReg
        val literal = assign.right as IrOp.ArrayLiteral
        assertEquals(2, literal.elements.size)
        assertTrue(literal.elements[0] is IrOp.ArrayElement.Spread)
        assertTrue(literal.elements[1] is IrOp.ArrayElement.Spread)
        assertTrue(result.none { it is IrOp.SpreadIntoArray })
    }

    @Test
    fun `merge literal elements with spread`() {
        val ops = listOf(
            IrOp.AssignReg(
                FunSimCtx.RegId.ACC,
                IrOp.ArrayLiteral(listOf(
                    IrOp.ArrayElement.Expr(IrOp.JustImm(JSValue.Number(1))),
                    IrOp.ArrayElement.Expr(IrOp.JustImm(JSValue.Number(2)))
                ))
            ),
            IrOp.AssignReg(v0, IrOp.LoadReg.acc),
            IrOp.AssignReg(FunSimCtx.RegId.ACC, IrOp.LoadReg(v1)),
            IrOp.SpreadIntoArray(v0, IrOp.LoadReg.acc)
        )

        val result = ArraySpreadMergingPass.run(ops)

        val assign = result.single { it is IrOp.AssignReg && it.left == v0 } as IrOp.AssignReg
        val literal = assign.right as IrOp.ArrayLiteral
        assertEquals(3, literal.elements.size)
        assertTrue(literal.elements[2] is IrOp.ArrayElement.Spread)
    }

    @Test
    fun `fallback when array register is overwritten`() {
        val ops = listOf(
            IrOp.AssignReg(FunSimCtx.RegId.ACC, IrOp.ArrayLiteral(emptyList())),
            IrOp.AssignReg(v0, IrOp.LoadReg.acc),
            IrOp.AssignReg(v0, IrOp.JustImm(JSValue.Number(0))),
            IrOp.AssignReg(FunSimCtx.RegId.ACC, IrOp.LoadReg(v1)),
            IrOp.SpreadIntoArray(v0, IrOp.LoadReg.acc)
        )

        val result = ArraySpreadMergingPass.run(ops)

        assertTrue(result.any { it is IrOp.SpreadIntoArray })
    }

    @Test
    fun `fallback when source has side effects`() {
        val ops = listOf(
            IrOp.AssignReg(FunSimCtx.RegId.ACC, IrOp.ArrayLiteral(emptyList())),
            IrOp.AssignReg(v0, IrOp.LoadReg.acc),
            IrOp.AssignReg(FunSimCtx.RegId.ACC, IrOp.CallAcc(listOf(v1), null)),
            IrOp.SpreadIntoArray(v0, IrOp.LoadReg.acc)
        )

        val result = ArraySpreadMergingPass.run(ops)

        assertTrue(result.any { it is IrOp.SpreadIntoArray })
    }

    @Test
    fun `copy propagation of array literal`() {
        val ops = listOf(
            IrOp.AssignReg(FunSimCtx.RegId.ACC, IrOp.ArrayLiteral(emptyList())),
            IrOp.AssignReg(v0, IrOp.LoadReg.acc),
            IrOp.AssignReg(v1, IrOp.LoadReg(v0)),
            IrOp.AssignReg(FunSimCtx.RegId.ACC, IrOp.LoadReg(v2)),
            IrOp.SpreadIntoArray(v1, IrOp.LoadReg.acc)
        )

        val result = ArraySpreadMergingPass.run(ops)

        val assign = result.first { it is IrOp.AssignReg && it.left == v1 } as IrOp.AssignReg
        assertTrue(assign.right is IrOp.ArrayLiteral)
        assertTrue(result.none { it is IrOp.SpreadIntoArray })
    }
}
