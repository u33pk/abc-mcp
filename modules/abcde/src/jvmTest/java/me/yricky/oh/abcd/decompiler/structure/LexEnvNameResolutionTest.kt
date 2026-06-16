package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.decompiler.behaviour.FunSimCtx
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.decompiler.behaviour.st2Acc
import org.junit.Assert.*
import org.junit.Test

/**
 * 词法环境名称解析单元测试
 */
class LexEnvNameResolutionTest {

    @Test
    fun testBuildLexEnvStacks() {
        val ops = listOf(
            IrOp.NewLexEnvWithName(2, listOf("name", "value")),
            IrOp.AssignReg(FunSimCtx.RegId.lexId(0, 0), IrOp.LoadReg.acc),
            IrOp.AssignReg(FunSimCtx.RegId.lexId(0, 1), IrOp.LoadReg.acc),
            IrOp.PopLexEnv,
            IrOp.NewLexEnv(1),
            IrOp.AssignReg(FunSimCtx.RegId.lexId(0, 0), IrOp.LoadReg.acc),
        )

        val stacks = LexEnvNameResolver.buildLexEnvStacks(ops)
        assertEquals(ops.size, stacks.size)

        // 第一条指令之前栈为空
        assertTrue(stacks[0].isEmpty())

        // newlexenvwithname 之后栈中有一个环境
        assertEquals(1, stacks[1].size)
        assertEquals(listOf("name", "value"), stacks[1][0])

        // stlexvar 不修改栈
        assertEquals(1, stacks[2].size)
        assertEquals(1, stacks[3].size)

        // poplexenv 之后栈为空
        assertTrue(stacks[4].isEmpty())

        // newlexenv 之后栈中有一个空名称环境
        assertEquals(1, stacks[5].size)
        assertEquals(listOf(null), stacks[5][0])
    }

    @Test
    fun testResolveLexName() {
        val stack = listOf(
            listOf("outer0", "outer1"),
            listOf("inner0", "inner1", "inner2")
        )

        // lvl=0 指向最内层（stack 最后一个元素）
        assertEquals("inner0", LexEnvNameResolver.resolveLexName(stack, 0, 0))
        assertEquals("inner2", LexEnvNameResolver.resolveLexName(stack, 0, 2))

        // lvl=1 指向外层
        assertEquals("outer0", LexEnvNameResolver.resolveLexName(stack, 1, 0))
        assertEquals("outer1", LexEnvNameResolver.resolveLexName(stack, 1, 1))

        // 越界返回 null
        assertNull(LexEnvNameResolver.resolveLexName(stack, 0, 10))
        assertNull(LexEnvNameResolver.resolveLexName(stack, 5, 0))
        assertNull(LexEnvNameResolver.resolveLexName(emptyList(), 0, 0))
    }

    @Test
    fun testLexLvlSlot() {
        val reg0 = FunSimCtx.RegId.lexId(0, 3)
        val (lvl0, slot0) = reg0.lexLvlSlot()!!
        assertEquals(0, lvl0)
        assertEquals(3, slot0)

        val reg1 = FunSimCtx.RegId.lexId(1, 5)
        val (lvl1, slot1) = reg1.lexLvlSlot()!!
        assertEquals(1, lvl1)
        assertEquals(5, slot1)

        assertNull(FunSimCtx.RegId.ACC.lexLvlSlot())
        assertNull(FunSimCtx.RegId.regId(0).lexLvlSlot())
    }

    @Test
    fun testNestedEnvNameResolution() {
        val ops = listOf(
            IrOp.NewLexEnvWithName(2, listOf("outerA", "outerB")),
            IrOp.NewLexEnvWithName(1, listOf("innerA")),
            IrOp.LoadReg(FunSimCtx.RegId.lexId(0, 0)).st2Acc(),
            IrOp.LoadReg(FunSimCtx.RegId.lexId(1, 0)).st2Acc(),
            IrOp.PopLexEnv,
            IrOp.LoadReg(FunSimCtx.RegId.lexId(0, 1)).st2Acc(),
        )

        val stacks = LexEnvNameResolver.buildLexEnvStacks(ops)

        // 读取 innerA（lvl=0, slot=0）时，应解析为 innerA
        assertEquals("innerA", LexEnvNameResolver.resolveLexName(stacks[2], 0, 0))

        // 读取 outerA（lvl=1, slot=0）时，应解析为 outerA
        assertEquals("outerA", LexEnvNameResolver.resolveLexName(stacks[3], 1, 0))

        // pop 之后读取 outerB（lvl=0, slot=1）时，应解析为 outerB
        assertEquals("outerB", LexEnvNameResolver.resolveLexName(stacks[5], 0, 1))
    }
}
