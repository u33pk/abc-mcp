package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.decompiler.behaviour.JSValue
import org.junit.Assert.*
import org.junit.Test

/**
 * 代数化简 / 常量折叠 Pass 单元测试
 */
class AlgebraicSimplificationPassTest {

    private fun num(value: Double) = IrOp.JustImm(JSValue.Number(value))
    private fun num(value: Int) = IrOp.JustImm(JSValue.Number(value))
    private fun str(value: String) = IrOp.JustImm(JSValue.Str(value))
    private fun bool(value: Boolean) = IrOp.JustImm(if (value) JSValue.True else JSValue.False)

    private fun runPass(vararg ops: IrOp): List<IrOp> {
        return AlgebraicSimplificationPass.run(ops.toList())
    }

    private fun assertNumber(value: Double, expr: IrOp.Expression) {
        assertTrue("Expected number constant", expr is IrOp.JustImm && expr.value is JSValue.Number)
        val actual = (expr as IrOp.JustImm).value as JSValue.Number
        assertEquals(value, actual.value.toDouble(), 0.0)
    }

    private fun assertBoolean(value: Boolean, expr: IrOp.Expression) {
        assertTrue("Expected boolean constant", expr is IrOp.JustImm)
        val actual = (expr as IrOp.JustImm).value
        assertEquals(if (value) JSValue.True else JSValue.False, actual)
    }

    private fun assertString(value: String, expr: IrOp.Expression) {
        assertTrue("Expected string constant", expr is IrOp.JustImm && expr.value is JSValue.Str)
        assertEquals(value, ((expr as IrOp.JustImm).value as JSValue.Str).value)
    }

    private fun isNaNValue(expr: IrOp.Expression): Boolean {
        if (expr !is IrOp.JustImm) return false
        return expr.value === JSValue.Nan ||
            (expr.value is JSValue.Number && expr.value.value.toDouble().isNaN())
    }

    private fun isInfinityValue(expr: IrOp.Expression): Boolean {
        if (expr !is IrOp.JustImm) return false
        return expr.value === JSValue.Infinity ||
            (expr.value is JSValue.Number && expr.value.value.toDouble().isInfinite())
    }

    @Test
    fun testAdd() {
        val result = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.Add(num(1), num(2))))
        assertNumber(3.0, (result[0] as IrOp.AssignReg).right)
    }

    @Test
    fun testSub() {
        val result = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.Sub(num(5), num(3))))
        assertNumber(2.0, (result[0] as IrOp.AssignReg).right)
    }

    @Test
    fun testMul() {
        val result = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.Mul(num(3), num(4))))
        assertNumber(12.0, (result[0] as IrOp.AssignReg).right)
    }

    @Test
    fun testDivByZero() {
        val result = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.Div(num(1), num(0))))
        val expr = (result[0] as IrOp.AssignReg).right
        assertTrue("Expected Infinity", isInfinityValue(expr))
    }

    @Test
    fun testZeroDivZero() {
        val result = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.Div(num(0), num(0))))
        val expr = (result[0] as IrOp.AssignReg).right
        assertTrue("Expected NaN", isNaNValue(expr))
    }

    @Test
    fun testMod() {
        val result = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.Mod(num(10), num(3))))
        assertNumber(1.0, (result[0] as IrOp.AssignReg).right)
    }

    @Test
    fun testIdentityWithIntegerZero() {
        // 验证整数 0 也能命中恒等规则：0 + x -> x
        val x = IrOp.LoadReg(IrOp.LoadReg.ACC)
        val result = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.Add(num(0), x)))
        assertEquals(x, (result[0] as IrOp.AssignReg).right)
    }

    @Test
    fun testNestedConstantFolding() {
        // (1 + 2) * (4 - 1) -> 9
        val innerL = IrOp.BiExp.Add(num(1), num(2))
        val innerR = IrOp.BiExp.Sub(num(4), num(1))
        val result = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.Mul(innerL, innerR)))
        assertNumber(9.0, (result[0] as IrOp.AssignReg).right)
    }

    @Test
    fun testShift() {
        val shl = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.Shl(num(1), num(3))))
        assertNumber(8.0, (shl[0] as IrOp.AssignReg).right)

        val ashr = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.AShr(num(-8), num(2))))
        assertNumber(-2.0, (ashr[0] as IrOp.AssignReg).right)

        val shr = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.Shr(num(-1), num(1))))
        // -1 >>> 1 = 0x7fffffff
        assertEquals(0x7fffffff.toDouble(), ((shr[0] as IrOp.AssignReg).right as IrOp.JustImm).value.let { (it as JSValue.Number).value.toDouble() }, 0.0)
    }

    @Test
    fun testBitwise() {
        val and = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.And(num(0b1100), num(0b1010))))
        assertNumber(0b1000.toDouble(), (and[0] as IrOp.AssignReg).right)

        val or = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.Or(num(0b1100), num(0b1010))))
        assertNumber(0b1110.toDouble(), (or[0] as IrOp.AssignReg).right)

        val xor = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.Xor(num(0b1100), num(0b1010))))
        assertNumber(0b0110.toDouble(), (xor[0] as IrOp.AssignReg).right)
    }

    @Test
    fun testPower() {
        val result = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.Exp(num(2), num(3))))
        assertNumber(8.0, (result[0] as IrOp.AssignReg).right)
    }

    @Test
    fun testStringConcat() {
        // 验证同一表达式内的字符串拼接折叠：("hello" + " ") + "world"
        val inner = IrOp.BiExp.Add(str("hello"), str(" "))
        val result = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.Add(inner, str("world"))))
        assertString("hello world", (result[0] as IrOp.AssignReg).right)
    }

    @Test
    fun testStrictEquality() {
        val t = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.StrictEq(num(1), num(1))))
        assertBoolean(true, (t[0] as IrOp.AssignReg).right)

        val f = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.StrictEq(num(1), str("1"))))
        assertBoolean(false, (f[0] as IrOp.AssignReg).right)

        val nan = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.StrictEq(IrOp.JustImm(JSValue.Nan), IrOp.JustImm(JSValue.Nan))))
        assertBoolean(false, (nan[0] as IrOp.AssignReg).right)
    }

    @Test
    fun testRelational() {
        val less = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.Less(num(1), num(2))))
        assertBoolean(true, (less[0] as IrOp.AssignReg).right)

        val ge = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.Ge(num(1), num(2))))
        assertBoolean(false, (ge[0] as IrOp.AssignReg).right)
    }

    @Test
    fun testIsTrueAndTypeOf() {
        val t = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.UaExp.IsTrue(num(1))))
        assertBoolean(true, (t[0] as IrOp.AssignReg).right)

        val f = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.UaExp.IsFalse(str(""))))
        assertBoolean(true, (f[0] as IrOp.AssignReg).right)

        val type = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.UaExp.TypeOf(str("x"))))
        assertString("string", (type[0] as IrOp.AssignReg).right)
    }

    @Test
    fun testToNumber() {
        val n = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.UaExp.ToNumber(str("42"))))
        assertNumber(42.0, (n[0] as IrOp.AssignReg).right)

        val nan = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.UaExp.ToNumber(str("abc"))))
        val expr = (nan[0] as IrOp.AssignReg).right
        assertTrue("Expected NaN", expr is IrOp.JustImm && expr.value === JSValue.Nan)
    }

    @Test
    fun testBigInt() {
        val a = IrOp.JustImm(JSValue.BigInt("100000000000000000000"))
        val b = IrOp.JustImm(JSValue.BigInt("1"))
        val result = runPass(IrOp.AssignReg(IrOp.LoadReg.ACC, IrOp.BiExp.Add(a, b)))
        assertTrue("Expected BigInt constant", (result[0] as IrOp.AssignReg).right is IrOp.JustImm)
        assertEquals("100000000000000000001", ((result[0] as IrOp.AssignReg).right as IrOp.JustImm).value.let { (it as JSValue.BigInt).value })
    }
}
