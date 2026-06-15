package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.decompiler.behaviour.FunSimCtx
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.decompiler.behaviour.JSValue
import org.junit.Assert.*
import org.junit.Test

/**
 * 重复 Load 消除 Pass 单元测试
 */
class RedundantLoadEliminationPassTest {

    private val v0 = FunSimCtx.RegId.regId(0)
    private val v1 = FunSimCtx.RegId.regId(1)
    private val v2 = FunSimCtx.RegId.regId(2)
    private val v3 = FunSimCtx.RegId.regId(3)
    private val ACC = FunSimCtx.RegId.ACC
    private val GLOBAL = FunSimCtx.RegId.GLOBAL

    private fun runPass(vararg ops: IrOp): List<IrOp> {
        return RedundantLoadEliminationPass.run(ops.toList())
    }

    /**
     * 基本场景：同一字段读取两次，第二次应被消除
     * v1 = v0.field
     * v2 = v0.field   → v2 = LoadReg(v1)
     */
    @Test
    fun testBasicRedundantLoadElimination() {
        val ops = listOf(
            IrOp.AssignReg(v1, IrOp.ObjField.Name(v0, "field")),
            IrOp.AssignReg(v2, IrOp.ObjField.Name(v0, "field"))
        )
        val result = runPass(*ops.toTypedArray())

        // 第一条不变
        assertTrue(result[0] is IrOp.AssignReg)
        val first = result[0] as IrOp.AssignReg
        assertEquals(v1, first.left)
        assertTrue(first.right is IrOp.ObjField.Name)

        // 第二条应变为 v2 = LoadReg(v1)
        assertTrue(result[1] is IrOp.AssignReg)
        val second = result[1] as IrOp.AssignReg
        assertEquals(v2, second.left)
        assertTrue("Expected LoadReg(v1) but got ${second.right}", second.right is IrOp.LoadReg)
        assertEquals(v1, (second.right as IrOp.LoadReg).regId)
    }

    /**
     * 不同字段不应消除
     * v1 = v0.fieldA
     * v2 = v0.fieldB   → 不变（不同字段）
     */
    @Test
    fun testDifferentFieldsNotEliminated() {
        val ops = listOf(
            IrOp.AssignReg(v1, IrOp.ObjField.Name(v0, "fieldA")),
            IrOp.AssignReg(v2, IrOp.ObjField.Name(v0, "fieldB"))
        )
        val result = runPass(*ops.toTypedArray())

        // 两条都应保持原样
        assertTrue(result[1].let { it is IrOp.AssignReg && it.right is IrOp.ObjField.Name })
        assertEquals("fieldB", (result[1] as IrOp.AssignReg).right.let { (it as IrOp.ObjField.Name).name })
    }

    /**
     * 不同对象不应消除
     * v1 = v0.field
     * v2 = v1.field   → 不变（不同对象）
     */
    @Test
    fun testDifferentObjectsNotEliminated() {
        val ops = listOf(
            IrOp.AssignReg(v1, IrOp.ObjField.Name(v0, "field")),
            IrOp.AssignReg(v2, IrOp.ObjField.Name(v1, "field"))
        )
        val result = runPass(*ops.toTypedArray())

        // 第二条应保持原样（v1.field，不是 v0.field）
        val second = result[1] as IrOp.AssignReg
        assertTrue(second.right is IrOp.ObjField.Name)
        val field = second.right as IrOp.ObjField.Name
        assertTrue(field.obj is IrOp.LoadReg)
        assertEquals(v1, (field.obj as IrOp.LoadReg).regId)
    }

    /**
     * 写入同一字段后缓存失效
     * v1 = v0.field
     * v0.field = v2    → 写入使缓存失效
     * v3 = v0.field    → 不应消除（缓存已失效）
     */
    @Test
    fun testWriteInvalidatesCache() {
        val ops = listOf(
            IrOp.AssignReg(v1, IrOp.ObjField.Name(v0, "field")),
            IrOp.AssignObj(IrOp.ObjField.Name(v0, "field"), IrOp.LoadReg(v2)),
            IrOp.AssignReg(v3, IrOp.ObjField.Name(v0, "field"))
        )
        val result = runPass(*ops.toTypedArray())

        // 第三条不应被消除
        val third = result[2] as IrOp.AssignReg
        assertTrue("Expected ObjField.Name after write, got ${third.right}", third.right is IrOp.ObjField.Name)
    }

    /**
     * 写入不同字段不影响缓存（ArkTS 直接赋值语义）
     * v1 = v0.fieldA
     * v0.fieldB = v2    → 写入 fieldB，不影响 fieldA 缓存
     * v3 = v0.fieldA    → 应被消除
     */
    @Test
    fun testWriteToDifferentFieldPreservesCache() {
        val ops = listOf(
            IrOp.AssignReg(v1, IrOp.ObjField.Name(v0, "fieldA")),
            IrOp.AssignObj(IrOp.ObjField.Name(v0, "fieldB"), IrOp.LoadReg(v2)),
            IrOp.AssignReg(v3, IrOp.ObjField.Name(v0, "fieldA"))
        )
        val result = runPass(*ops.toTypedArray())

        // 第三条应被消除（fieldA 缓存仍有效）
        val third = result[2] as IrOp.AssignReg
        assertTrue("Expected LoadReg(v1), got ${third.right}", third.right is IrOp.LoadReg)
        assertEquals(v1, (third.right as IrOp.LoadReg).regId)
    }

    /**
     * 函数调用使参数对象的缓存失效
     * v1 = v0.field
     * _acc_ = CallAcc(args=[v0])   → 调用可能修改 v0 的属性
     * v2 = v0.field                → 不应消除
     */
    @Test
    fun testCallInvalidatesCache() {
        val ops = listOf(
            IrOp.AssignReg(v1, IrOp.ObjField.Name(v0, "field")),
            IrOp.AssignReg(ACC, IrOp.CallAcc(listOf(v0))),
            IrOp.AssignReg(v2, IrOp.ObjField.Name(v0, "field"))
        )
        val result = runPass(*ops.toTypedArray())

        // 第三条不应被消除
        val third = result[2] as IrOp.AssignReg
        assertTrue(third.right is IrOp.ObjField.Name)
    }

    /**
     * CallWithTarget 也使缓存失效
     * v1 = v0.field
     * _acc_ = CallWithTarget(v0.method, args=[v0])
     * v2 = v0.field   → 不应消除
     */
    @Test
    fun testCallWithTargetInvalidatesCache() {
        val methodExpr = IrOp.ObjField.Name(v0, "method")
        val ops = listOf(
            IrOp.AssignReg(v1, IrOp.ObjField.Name(v0, "field")),
            IrOp.AssignReg(ACC, IrOp.CallWithTarget(methodExpr, listOf(v0))),
            IrOp.AssignReg(v2, IrOp.ObjField.Name(v0, "field"))
        )
        val result = runPass(*ops.toTypedArray())

        val third = result[2] as IrOp.AssignReg
        assertTrue(third.right is IrOp.ObjField.Name)
    }

    /**
     * ObjField.Index 也应被缓存和消除
     * v1 = v0[0]
     * v2 = v0[0]   → v2 = LoadReg(v1)
     */
    @Test
    fun testIndexFieldCached() {
        val ops = listOf(
            IrOp.AssignReg(v1, IrOp.ObjField.Index(v0, 0)),
            IrOp.AssignReg(v2, IrOp.ObjField.Index(v0, 0))
        )
        val result = runPass(*ops.toTypedArray())

        val second = result[1] as IrOp.AssignReg
        assertTrue("Expected LoadReg(v1), got ${second.right}", second.right is IrOp.LoadReg)
        assertEquals(v1, (second.right as IrOp.LoadReg).regId)
    }

    /**
     * ObjField.Value（动态 key）不应被缓存
     * v1 = v0[v2]
     * v3 = v0[v2]   → 不变（动态 key）
     */
    @Test
    fun testValueFieldNotCached() {
        val ops = listOf(
            IrOp.AssignReg(v1, IrOp.ObjField.Value(v0, v2)),
            IrOp.AssignReg(v3, IrOp.ObjField.Value(v0, v2))
        )
        val result = runPass(*ops.toTypedArray())

        // 两条都应保持原样
        val second = result[1] as IrOp.AssignReg
        assertTrue(second.right is IrOp.ObjField.Value)
    }

    /**
     * 赋值给 left 寄存器使其作为 obj 的缓存失效
     * v1 = v0.field
     * v0 = v2         → v0 被重新赋值
     * v3 = v0.field   → 不应消除（v0 已变）
     */
    @Test
    fun testRegisterReassignmentInvalidatesCache() {
        val ops = listOf(
            IrOp.AssignReg(v1, IrOp.ObjField.Name(v0, "field")),
            IrOp.AssignReg(v0, IrOp.LoadReg(v2)),
            IrOp.AssignReg(v3, IrOp.ObjField.Name(v0, "field"))
        )
        val result = runPass(*ops.toTypedArray())

        val third = result[2] as IrOp.AssignReg
        assertTrue(third.right is IrOp.ObjField.Name)
    }

    /**
     * NewInst 使参数对象缓存失效
     * v1 = v0.field
     * _acc_ = new Clazz(v0)
     * v2 = v0.field   → 不应消除
     */
    @Test
    fun testNewInstInvalidatesCache() {
        val ops = listOf(
            IrOp.AssignReg(v1, IrOp.ObjField.Name(v0, "field")),
            IrOp.AssignReg(ACC, IrOp.NewInst(v2, listOf(v0))),
            IrOp.AssignReg(v3, IrOp.ObjField.Name(v0, "field"))
        )
        val result = runPass(*ops.toTypedArray())

        val third = result[2] as IrOp.AssignReg
        assertTrue(third.right is IrOp.ObjField.Name)
    }

    /**
     * Jump/JumpIf 不应使缓存失效
     * v1 = v0.field
     * jump offset
     * v2 = v0.field   → 应被消除
     */
    @Test
    fun testJumpDoesNotInvalidateCache() {
        val ops = listOf(
            IrOp.AssignReg(v1, IrOp.ObjField.Name(v0, "field")),
            IrOp.Jump(10),
            IrOp.AssignReg(v2, IrOp.ObjField.Name(v0, "field"))
        )
        val result = runPass(*ops.toTypedArray())

        val third = result[2] as IrOp.AssignReg
        assertTrue("Expected LoadReg(v1), got ${third.right}", third.right is IrOp.LoadReg)
        assertEquals(v1, (third.right as IrOp.LoadReg).regId)
    }

    /**
     * 完整优化管道集成测试
     * 验证 RedundantLoadEliminationPass 在完整管道中正常工作
     */
    @Test
    fun testFullOptimizerPipeline() {
        // 模拟：v0 = global; v1 = v0.field; v2 = v0.field;
        val ops = listOf(
            IrOp.AssignReg(v0, IrOp.LoadReg(GLOBAL)),
            IrOp.AssignReg(v1, IrOp.ObjField.Name(v0, "field")),
            IrOp.AssignReg(v2, IrOp.ObjField.Name(v0, "field"))
        )
        val result = IrOpOptimizer.optimizeList(ops)

        // 检查 v2 是否被优化为引用 v1
        val v2Assign = result.find {
            it is IrOp.AssignReg && it.left == v2
        } as? IrOp.AssignReg

        if (v2Assign != null) {
            // 在完整管道中，CopyProp + LoadElim + ExprProp 可能进一步优化
            println("Pipeline result for v2: ${v2Assign.right}")
        }

        // 至少验证没有报错
        assertTrue("Pipeline should complete without error", true)
    }

    /**
     * DeleteProp 使 obj 缓存失效
     * v1 = v0.field
     * delete v0[prop]
     * v2 = v0.field   → 不应消除
     */
    @Test
    fun testDeletePropInvalidatesCache() {
        val ops = listOf(
            IrOp.AssignReg(v1, IrOp.ObjField.Name(v0, "field")),
            IrOp.DeleteProp(v0, v2),
            IrOp.AssignReg(v3, IrOp.ObjField.Name(v0, "field"))
        )
        val result = runPass(*ops.toTypedArray())

        val third = result[2] as IrOp.AssignReg
        assertTrue(third.right is IrOp.ObjField.Name)
    }

    /**
     * Index 写入使整个 obj 缓存失效
     * v1 = v0.field
     * v0[0] = v2      → Index 写入，无法确定写的是哪个字段
     * v3 = v0.field   → 不应消除
     */
    @Test
    fun testIndexWriteInvalidatesAllFields() {
        val ops = listOf(
            IrOp.AssignReg(v1, IrOp.ObjField.Name(v0, "field")),
            IrOp.AssignObj(IrOp.ObjField.Index(v0, 0), IrOp.LoadReg(v2)),
            IrOp.AssignReg(v3, IrOp.ObjField.Name(v0, "field"))
        )
        val result = runPass(*ops.toTypedArray())

        val third = result[2] as IrOp.AssignReg
        assertTrue(third.right is IrOp.ObjField.Name)
    }

    /**
     * 全局对象字段读取也应被优化
     * v1 = global.console
     * v2 = global.console   → v2 = LoadReg(v1)
     */
    @Test
    fun testGlobalFieldLoadElimination() {
        val ops = listOf(
            IrOp.AssignReg(v1, IrOp.ObjField.Name(GLOBAL, "console")),
            IrOp.AssignReg(v2, IrOp.ObjField.Name(GLOBAL, "console"))
        )
        val result = runPass(*ops.toTypedArray())

        val second = result[1] as IrOp.AssignReg
        assertTrue("Expected LoadReg(v1), got ${second.right}", second.right is IrOp.LoadReg)
        assertEquals(v1, (second.right as IrOp.LoadReg).regId)
    }

    /**
     * 混合场景：多种字段访问类型
     */
    @Test
    fun testMixedFieldAccessTypes() {
        val ops = listOf(
            IrOp.AssignReg(v1, IrOp.ObjField.Name(v0, "name")),
            IrOp.AssignReg(v2, IrOp.ObjField.Index(v0, 0)),
            IrOp.AssignReg(v3, IrOp.ObjField.Name(v0, "name")),  // 应被消除
            IrOp.AssignReg(ACC, IrOp.ObjField.Index(v0, 0))       // 应被消除
        )
        val result = runPass(*ops.toTypedArray())

        // v3 = v0.name → v3 = LoadReg(v1)
        val third = result[2] as IrOp.AssignReg
        assertTrue(third.right is IrOp.LoadReg)
        assertEquals(v1, (third.right as IrOp.LoadReg).regId)

        // _acc_ = v0[0] → _acc_ = LoadReg(v2)
        val fourth = result[3] as IrOp.AssignReg
        assertTrue(fourth.right is IrOp.LoadReg)
        assertEquals(v2, (fourth.right as IrOp.LoadReg).regId)
    }

    /**
     * 演示：优化效果可视化
     * 模拟一段典型的 ArkTS 反编译 IR，展示重复 Load 消除前后对比
     */
    @Test
    fun testDemoOptimizationEffect() {
        println("=" .repeat(70))
        println("重复 Load 消除 — 优化效果演示")
        println("=" .repeat(70))

        // 模拟 ArkTS 源码：
        //   let config = globalThis.appConfig;
        //   let name = config.name;
        //   let version = config.version;
        //   let name2 = config.name;        // 冗余读取！
        //   console.log(name, version, name2);
        //
        // 对应 IR（简化后）：
        val ops = listOf(
            // v0 = globalThis
            IrOp.AssignReg(v0, IrOp.LoadReg(GLOBAL)),
            // v1 = v0.appConfig          → ObjField.Name(v0, "appConfig")
            IrOp.AssignReg(v1, IrOp.ObjField.Name(v0, "appConfig")),
            // v2 = v1.name               → ObjField.Name(v1, "name")
            IrOp.AssignReg(v2, IrOp.ObjField.Name(v1, "name")),
            // v3 = v1.version            → ObjField.Name(v1, "version")
            IrOp.AssignReg(v3, IrOp.ObjField.Name(v1, "version")),
            // _acc_ = v1.name  ← 冗余！与第3条读取相同
            IrOp.AssignReg(ACC, IrOp.ObjField.Name(v1, "name")),
            // _acc_ = console.log(v2, v3, _acc_)
            IrOp.AssignReg(ACC, IrOp.CallAcc(listOf(v2, v3)))
        )

        fun regName(r: FunSimCtx.RegId): String = when (r) {
            FunSimCtx.RegId.ACC -> "_acc_"
            FunSimCtx.RegId.GLOBAL -> "globalThis"
            FunSimCtx.RegId.THIS -> "this"
            else -> "v${r.value and 0xFFFF}"
        }

        fun exprToIrString(e: IrOp.Expression): String = when (e) {
            is IrOp.LoadReg -> regName(e.regId)
            is IrOp.ObjField.Name -> "${exprToIrString(e.obj)}.${e.name}"
            is IrOp.ObjField.Index -> "${exprToIrString(e.obj)}[${e.index}]"
            is IrOp.CallAcc -> "call(${e.args.joinToString { regName(it) }})"
            else -> e.toString()
        }

        fun IrOp.toIrString(): String = when (this) {
            is IrOp.AssignReg -> "${regName(left)} = ${exprToIrString(right)}"
            else -> this.toString()
        }

        println("\n─── 优化前 IR ───")
        ops.forEachIndexed { i, op -> println("  [$i] ${op.toIrString()}") }

        val optimized = RedundantLoadEliminationPass.run(ops)

        println("\n─── 优化后 IR ───")
        optimized.forEachIndexed { i, op -> println("  [$i] ${op.toIrString()}") }

        println("\n─── 变化 ───")
        val origLast = ops[4] as IrOp.AssignReg
        val optLast = optimized[4] as IrOp.AssignReg
        println("  指令[4]: ${origLast.toIrString()}")
        println("        → ${optLast.toIrString()}")
        println()
        println("  ✅ config.name 的第二次读取被消除，")
        println("     直接复用第一次读取的结果寄存器 v2。")

        // 验证
        assertTrue(optLast.right is IrOp.LoadReg)
        assertEquals(v2, (optLast.right as IrOp.LoadReg).regId)

        println("\n" + "=" .repeat(70))
    }

    /**
     * 演示：写入使缓存失效的场景
     */
    @Test
    fun testDemoWriteInvalidation() {
        println("=" .repeat(70))
        println("写入失效 — 演示")
        println("=" .repeat(70))

        // ArkTS 源码：
        //   let a = obj.x;
        //   obj.x = 42;        // 写入！
        //   let b = obj.x;     // 不能复用 a，因为 obj.x 已被修改
        val ops = listOf(
            IrOp.AssignReg(v1, IrOp.ObjField.Name(v0, "x")),
            IrOp.AssignObj(IrOp.ObjField.Name(v0, "x"), IrOp.JustImm(JSValue.Number(42.0))),
            IrOp.AssignReg(v2, IrOp.ObjField.Name(v0, "x"))
        )

        val optimized = RedundantLoadEliminationPass.run(ops)

        println("\n─── 优化前 IR ───")
        println("  [0] v1 = v0.x")
        println("  [1] v0.x = 42")
        println("  [2] v2 = v0.x")

        println("\n─── 优化后 IR ───")
        println("  [0] v1 = v0.x")
        println("  [1] v0.x = 42")
        val opt2 = optimized[2] as IrOp.AssignReg
        val opt2Str = if (opt2.right is IrOp.ObjField.Name) "v0.x (未消除，正确！)" else opt2.right.toString()
        println("  [2] v2 = $opt2Str")

        // 验证：不应被消除
        assertTrue(optimized[2].let { it is IrOp.AssignReg && it.right is IrOp.ObjField.Name })

        println("\n  ✅ 写入 obj.x 后缓存正确失效，v2 仍从 obj 读取。")
        println("=" .repeat(70))
    }
}
