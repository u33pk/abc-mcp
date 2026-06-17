package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.decompiler.behaviour.FunSimCtx
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.decompiler.behaviour.JSValue
import me.yricky.oh.abcd.decompiler.behaviour.assignLeftAcc
import me.yricky.oh.abcd.decompiler.behaviour.assignRightAcc
import me.yricky.oh.abcd.decompiler.behaviour.replaceReg
import java.math.BigInteger
import kotlin.math.pow

/**
 * 优化 Pass 接口
 */
interface OptimizationPass {
    fun run(ops: List<IrOp>): List<IrOp>
}

/**
 * 代数化简 Pass
 * 简化常量表达式和恒等变换
 */
object AlgebraicSimplificationPass : OptimizationPass {
    override fun run(ops: List<IrOp>): List<IrOp> {
        return ops.map { op ->
            when (op) {
                is IrOp.AssignReg -> IrOp.AssignReg(op.left, simplifyExpression(op.right))
                is IrOp.AssignObj -> IrOp.AssignObj(op.left, simplifyExpression(op.right))
                else -> op
            }
        }
    }

    /**
     * 公开的表达式简化接口，供 RegionGraphBuilder 等外部调用
     */
    fun simplify(expr: IrOp.Expression): IrOp.Expression = simplifyExpression(expr)

    /**
     * 判断表达式是否是常量值
     */
    fun isConstant(expr: IrOp.Expression): Boolean = expr is IrOp.JustImm

    private fun simplifyExpression(expr: IrOp.Expression): IrOp.Expression {
        return when (expr) {
            is IrOp.BiExp.Add -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                foldStringConcat(l, r)
                    ?: foldBigIntBinary(l, r, BigInteger::add)
                    ?: foldNumberBinary(l, r) { a, b -> a + b }
                    ?: when {
                        isZero(l) -> r
                        isZero(r) -> l
                        else -> IrOp.BiExp.Add(l, r)
                    }
            }
            is IrOp.BiExp.Sub -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                foldBigIntBinary(l, r, BigInteger::subtract)
                    ?: foldNumberBinary(l, r) { a, b -> a - b }
                    ?: when {
                        isZero(r) -> l
                        l == r -> zero()
                        else -> IrOp.BiExp.Sub(l, r)
                    }
            }
            is IrOp.BiExp.Mul -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                foldBigIntBinary(l, r, BigInteger::multiply)
                    ?: foldNumberBinary(l, r) { a, b -> a * b }
                    ?: when {
                        isZero(l) || isZero(r) -> zero()
                        isOne(l) -> r
                        isOne(r) -> l
                        else -> IrOp.BiExp.Mul(l, r)
                    }
            }
            is IrOp.BiExp.Div -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                foldBigIntBinary(l, r) { a, b -> if (b == BigInteger.ZERO) return@foldBigIntBinary null else a / b }
                    ?: foldNumberBinary(l, r) { a, b -> a / b }
                    ?: when {
                        isZero(l) -> zero()
                        isOne(r) -> l
                        else -> IrOp.BiExp.Div(l, r)
                    }
            }
            is IrOp.BiExp.Mod -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                foldBigIntBinary(l, r) { a, b -> if (b == BigInteger.ZERO) return@foldBigIntBinary null else a % b }
                    ?: foldNumberBinary(l, r) { a, b -> a % b }
                    ?: when {
                        isZero(l) -> zero()
                        isOne(r) -> zero()
                        else -> IrOp.BiExp.Mod(l, r)
                    }
            }
            is IrOp.BiExp.Shl -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                foldBitwiseBinary(l, r) { a, b -> a shl b }
                    ?: when {
                        isZero(l) -> zero()
                        isZero(r) -> l
                        else -> IrOp.BiExp.Shl(l, r)
                    }
            }
            is IrOp.BiExp.Shr -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                foldUnsignedShift(l, r)
                    ?: when {
                        isZero(l) -> zero()
                        isZero(r) -> l
                        else -> IrOp.BiExp.Shr(l, r)
                    }
            }
            is IrOp.BiExp.AShr -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                foldBitwiseBinary(l, r) { a, b -> a shr b }
                    ?: when {
                        isZero(l) -> zero()
                        isZero(r) -> l
                        else -> IrOp.BiExp.AShr(l, r)
                    }
            }
            is IrOp.BiExp.And -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                foldBitwiseBinary(l, r) { a, b -> a and b }
                    ?: when {
                        isZero(l) || isZero(r) -> zero()
                        else -> IrOp.BiExp.And(l, r)
                    }
            }
            is IrOp.BiExp.Or -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                foldBitwiseBinary(l, r) { a, b -> a or b }
                    ?: when {
                        isZero(l) -> r
                        isZero(r) -> l
                        else -> IrOp.BiExp.Or(l, r)
                    }
            }
            is IrOp.BiExp.Xor -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                foldBitwiseBinary(l, r) { a, b -> a xor b }
                    ?: when {
                        isZero(l) -> r
                        isZero(r) -> l
                        l == r -> zero()
                        else -> IrOp.BiExp.Xor(l, r)
                    }
            }
            is IrOp.BiExp.Exp -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                foldBigIntExp(l, r)
                    ?: foldNumberBinary(l, r) { a, b -> a.pow(b) }
                    ?: when {
                        isZero(r) -> one()
                        isOne(r) -> l
                        else -> IrOp.BiExp.Exp(l, r)
                    }
            }
            is IrOp.BiExp.Eq -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                foldAbstractEquality(l, r)?.let { boolExpr(it) }
                    ?: IrOp.BiExp.Eq(l, r)
            }
            is IrOp.BiExp.NEq -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                foldAbstractEquality(l, r)?.let { boolExpr(!it) }
                    ?: IrOp.BiExp.NEq(l, r)
            }
            is IrOp.BiExp.StrictEq -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                foldStrictEquality(l, r)?.let { boolExpr(it) }
                    ?: IrOp.BiExp.StrictEq(l, r)
            }
            is IrOp.BiExp.StrictNEq -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                foldStrictEquality(l, r)?.let { boolExpr(!it) }
                    ?: IrOp.BiExp.StrictNEq(l, r)
            }
            is IrOp.BiExp.Less -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                foldRelational(l, r) { a, b -> a < b }
                    ?: IrOp.BiExp.Less(l, r)
            }
            is IrOp.BiExp.LEq -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                foldRelational(l, r) { a, b -> a <= b }
                    ?: IrOp.BiExp.LEq(l, r)
            }
            is IrOp.BiExp.Ge -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                foldRelational(l, r) { a, b -> a >= b }
                    ?: IrOp.BiExp.Ge(l, r)
            }
            is IrOp.BiExp.GEq -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                foldRelational(l, r) { a, b -> a > b }
                    ?: IrOp.BiExp.GEq(l, r)
            }
            // Unary expressions
            is IrOp.UaExp.Neg -> {
                val s = simplifyExpression(expr.source)
                asNumber(s)?.let { numberExpr(-it) }
                    ?: asBigInt(s)?.let { IrOp.JustImm(JSValue.BigInt(it.negate().toString())) }
                    ?: IrOp.UaExp.Neg(s)
            }
            is IrOp.UaExp.Not -> {
                val s = simplifyExpression(expr.source)
                asNumber(s)?.toInt()?.let { numberExpr(it.inv().toDouble()) }
                    ?: IrOp.UaExp.Not(s)
            }
            is IrOp.UaExp.Inc -> {
                val s = simplifyExpression(expr.source)
                foldBigIntUnary(s) { it.add(BigInteger.ONE) }
                    ?: asNumber(s)?.let { numberExpr(it + 1) }
                    ?: IrOp.UaExp.Inc(s)
            }
            is IrOp.UaExp.Dec -> {
                val s = simplifyExpression(expr.source)
                foldBigIntUnary(s) { it.subtract(BigInteger.ONE) }
                    ?: asNumber(s)?.let { numberExpr(it - 1) }
                    ?: IrOp.UaExp.Dec(s)
            }
            is IrOp.UaExp.IsTrue -> {
                val s = simplifyExpression(expr.source)
                jsTruthy(s)?.let { boolExpr(it) } ?: IrOp.UaExp.IsTrue(s)
            }
            is IrOp.UaExp.IsFalse -> {
                val s = simplifyExpression(expr.source)
                jsTruthy(s)?.let { boolExpr(!it) } ?: IrOp.UaExp.IsFalse(s)
            }
            is IrOp.UaExp.TypeOf -> {
                val s = simplifyExpression(expr.source)
                jsTypeof(s)?.let { stringExpr(it) } ?: IrOp.UaExp.TypeOf(s)
            }
            is IrOp.UaExp.ToNumber -> {
                val s = simplifyExpression(expr.source)
                jsToNumber(s)?.let { if (it.isNaN()) IrOp.JustImm(JSValue.Nan) else numberExpr(it) }
                    ?: IrOp.UaExp.ToNumber(s)
            }
            is IrOp.UaExp.ToNumeric -> {
                val s = simplifyExpression(expr.source)
                jsToNumeric(s) ?: IrOp.UaExp.ToNumeric(s)
            }
            // Other expressions - recursively simplify children
            is IrOp.BiExp.InstOf -> IrOp.BiExp.InstOf(simplifyExpression(expr.l), simplifyExpression(expr.r))
            is IrOp.BiExp.IsIn -> IrOp.BiExp.IsIn(simplifyExpression(expr.l), simplifyExpression(expr.r))
            is IrOp.UaExp.GetTemplateObject -> IrOp.UaExp.GetTemplateObject(simplifyExpression(expr.source))
            else -> expr
        }
    }

    // region 常量提取辅助

    private fun asNumber(expr: IrOp.Expression): Double? =
        (expr as? IrOp.JustImm)?.value?.let { if (it is JSValue.Number) it.value.toDouble() else null }

    private fun asBigInt(expr: IrOp.Expression): BigInteger? =
        (expr as? IrOp.JustImm)?.value?.let {
            if (it is JSValue.BigInt) runCatching { BigInteger(it.value) }.getOrNull() else null
        }

    private fun asString(expr: IrOp.Expression): String? =
        (expr as? IrOp.JustImm)?.value?.let { if (it is JSValue.Str) it.value else null }

    private fun asBoolean(expr: IrOp.Expression): Boolean? =
        (expr as? IrOp.JustImm)?.value?.let {
            when (it) {
                JSValue.True -> true
                JSValue.False -> false
                else -> null
            }
        }

    private fun isZero(expr: IrOp.Expression): Boolean = asNumber(expr) == 0.0
    private fun isOne(expr: IrOp.Expression): Boolean = asNumber(expr) == 1.0

    private fun zero(): IrOp.Expression = IrOp.JustImm(JSValue.Number(0))
    private fun one(): IrOp.Expression = IrOp.JustImm(JSValue.Number(1))
    private fun numberExpr(d: Double): IrOp.Expression = IrOp.JustImm(JSValue.Number(d))
    private fun boolExpr(b: Boolean): IrOp.Expression =
        IrOp.JustImm(if (b) JSValue.True else JSValue.False)

    private fun stringExpr(s: String): IrOp.Expression = IrOp.JustImm(JSValue.Str(s))

    // endregion

    // region 二元常量折叠

    private fun foldNumberBinary(
        l: IrOp.Expression,
        r: IrOp.Expression,
        op: (Double, Double) -> Double
    ): IrOp.Expression? {
        val ln = asNumber(l)
        val rn = asNumber(r)
        return if (ln != null && rn != null) numberExpr(op(ln, rn)) else null
    }

    private fun foldBitwiseBinary(
        l: IrOp.Expression,
        r: IrOp.Expression,
        op: (Int, Int) -> Int
    ): IrOp.Expression? {
        val ln = asNumber(l)?.toInt()
        val rn = asNumber(r)?.toInt()
        return if (ln != null && rn != null) {
            val shift = rn and 0x1f
            numberExpr(op(ln, shift).toDouble())
        } else null
    }

    private fun foldUnsignedShift(
        l: IrOp.Expression,
        r: IrOp.Expression
    ): IrOp.Expression? {
        val ln = asNumber(l)?.toInt()
        val rn = asNumber(r)?.toInt()
        return if (ln != null && rn != null) {
            val shift = rn and 0x1f
            numberExpr((ln.toUInt() shr shift).toDouble())
        } else null
    }

    private fun foldBigIntBinary(
        l: IrOp.Expression,
        r: IrOp.Expression,
        op: (BigInteger, BigInteger) -> BigInteger?
    ): IrOp.Expression? {
        val lb = asBigInt(l)
        val rb = asBigInt(r)
        return if (lb != null && rb != null) {
            op(lb, rb)?.let { IrOp.JustImm(JSValue.BigInt(it.toString())) }
        } else null
    }

    private fun foldBigIntExp(
        l: IrOp.Expression,
        r: IrOp.Expression
    ): IrOp.Expression? {
        val lb = asBigInt(l)
        val rn = asNumber(r)
        return if (lb != null && rn != null && rn >= 0 && rn <= Int.MAX_VALUE && rn == rn.toInt().toDouble()) {
            try {
                IrOp.JustImm(JSValue.BigInt(lb.pow(rn.toInt()).toString()))
            } catch (_: Throwable) {
                null
            }
        } else null
    }

    private fun foldBigIntUnary(
        expr: IrOp.Expression,
        op: (BigInteger) -> BigInteger
    ): IrOp.Expression? {
        val b = asBigInt(expr) ?: return null
        return IrOp.JustImm(JSValue.BigInt(op(b).toString()))
    }

    private fun foldStringConcat(
        l: IrOp.Expression,
        r: IrOp.Expression
    ): IrOp.Expression? {
        val ls = asString(l)
        val rs = asString(r)
        if (ls != null && rs != null) return stringExpr(ls + rs)
        if (ls != null && r is IrOp.JustImm) return stringExpr(ls + jsValueToString(r.value))
        if (rs != null && l is IrOp.JustImm) return stringExpr(jsValueToString(l.value) + rs)
        return null
    }

    private fun foldStrictEquality(l: IrOp.Expression, r: IrOp.Expression): Boolean? {
        if (l !is IrOp.JustImm || r !is IrOp.JustImm) return null
        val lv = l.value
        val rv = r.value
        // NaN !== NaN
        if (lv === JSValue.Nan && rv === JSValue.Nan) return false
        if (lv::class != rv::class) return false
        return when {
            lv is JSValue.Number && rv is JSValue.Number -> lv.value.toDouble() == rv.value.toDouble()
            lv is JSValue.Str && rv is JSValue.Str -> lv.value == rv.value
            lv is JSValue.BigInt && rv is JSValue.BigInt -> BigInteger(lv.value) == BigInteger(rv.value)
            else -> lv === rv
        }
    }

    private fun foldAbstractEquality(l: IrOp.Expression, r: IrOp.Expression): Boolean? {
        if (l !is IrOp.JustImm || r !is IrOp.JustImm) return null
        val lv = l.value
        val rv = r.value
        // 仅对同类型安全求值；跨类型 == 涉及复杂隐式转换，暂不折叠
        if (lv::class != rv::class) {
            // null == undefined 在 JS 中为 true
            if ((lv === JSValue.Null && rv === JSValue.Undefined) ||
                (lv === JSValue.Undefined && rv === JSValue.Null)
            ) return true
            return null
        }
        if (lv is JSValue.Number && rv is JSValue.Number) {
            if (lv.value.toDouble().isNaN() || rv.value.toDouble().isNaN()) return false
            return lv.value.toDouble() == rv.value.toDouble()
        }
        return foldStrictEquality(l, r)
    }

    private fun foldRelational(
        l: IrOp.Expression,
        r: IrOp.Expression,
        op: (Double, Double) -> Boolean
    ): IrOp.Expression? {
        val ln = asNumber(l)
        val rn = asNumber(r)
        if (ln != null && rn != null) {
            if (ln.isNaN() || rn.isNaN()) return boolExpr(false)
            return boolExpr(op(ln, rn))
        }
        val ls = asString(l)
        val rs = asString(r)
        return if (ls != null && rs != null) {
            boolExpr(op(ls.compareTo(rs).toDouble(), 0.0))
        } else null
    }

    // endregion

    // region 一元/类型相关常量折叠

    private fun jsTruthy(expr: IrOp.Expression): Boolean? {
        return when (val v = (expr as? IrOp.JustImm)?.value) {
            JSValue.False, JSValue.Null, JSValue.Undefined -> false
            JSValue.True -> true
            JSValue.Nan -> false
            JSValue.Infinity -> true
            is JSValue.Number -> v.value.toDouble() != 0.0
            is JSValue.BigInt -> BigInteger(v.value) != BigInteger.ZERO
            is JSValue.Str -> v.value.isNotEmpty()
            else -> null
        }
    }

    private fun jsTypeof(expr: IrOp.Expression): String? {
        return when (val v = (expr as? IrOp.JustImm)?.value) {
            JSValue.Undefined -> "undefined"
            JSValue.Null -> "object"
            JSValue.True, JSValue.False -> "boolean"
            is JSValue.Number -> "number"
            is JSValue.BigInt -> "bigint"
            is JSValue.Str -> "string"
            else -> null
        }
    }

    private fun jsToNumber(expr: IrOp.Expression): Double? {
        return when (val v = (expr as? IrOp.JustImm)?.value) {
            JSValue.Undefined -> Double.NaN
            JSValue.Null -> 0.0
            JSValue.True -> 1.0
            JSValue.False -> 0.0
            is JSValue.Number -> v.value.toDouble()
            is JSValue.BigInt -> runCatching { BigInteger(v.value).toDouble() }.getOrNull() ?: Double.NaN
            is JSValue.Str -> v.value.toDoubleOrNull() ?: Double.NaN
            else -> null
        }
    }

    private fun jsToNumeric(expr: IrOp.Expression): IrOp.Expression? {
        return when (val v = (expr as? IrOp.JustImm)?.value) {
            is JSValue.Number -> expr
            is JSValue.BigInt -> expr
            JSValue.Undefined -> IrOp.JustImm(JSValue.Nan)
            JSValue.Null -> zero()
            JSValue.True -> one()
            JSValue.False -> zero()
            is JSValue.Str -> {
                val d = v.value.toDoubleOrNull() ?: Double.NaN
                if (d.isNaN()) IrOp.JustImm(JSValue.Nan) else numberExpr(d)
            }
            else -> null
        }
    }

    private fun jsValueToString(value: JSValue): String = when (value) {
        JSValue.Undefined -> "undefined"
        JSValue.Null -> "null"
        JSValue.True -> "true"
        JSValue.False -> "false"
        JSValue.Nan -> "NaN"
        JSValue.Infinity -> "Infinity"
        is JSValue.Number -> value.value.toDouble().toString()
        is JSValue.BigInt -> value.value + "n"
        is JSValue.Str -> value.value
        else -> ""
    }

    // endregion
}

/**
 * 拷贝传播 Pass
 * 将 _acc_ = xxx; yyy = _acc_; 简化为 yyy = xxx;
 * 以及寄存器间的拷贝传播
 */
object CopyPropagationPass : OptimizationPass {
    override fun run(ops: List<IrOp>): List<IrOp> {
        // 维护替换映射：reg -> 最近一次赋值的表达式
        val replacements = mutableMapOf<FunSimCtx.RegId, IrOp.Expression>()
        val result = mutableListOf<IrOp>()
        
        for (op in ops) {
            when (op) {
                is IrOp.AssignReg -> {
                    // 如果右值直接是已知寄存器，替换为其来源表达式
                    var newRight = op.right
                    if (newRight is IrOp.LoadReg && newRight.regId in replacements) {
                        newRight = replacements[newRight.regId]!!
                    }

                    result.add(IrOp.AssignReg(op.left, newRight))

                    // 更新映射
                    if (op.right is IrOp.LoadReg) {
                        replacements[op.left] = replacements[op.right.regId] ?: op.right
                    } else {
                        replacements[op.left] = newRight
                    }
                }
                is IrOp.AssignObj -> {
                    // 对象赋值可能影响所有已知映射，保守地清除
                    replacements.clear()
                    result.add(op)
                }
                is IrOp.Statement -> {
                    // 其他语句可能有副作用，清除 ACC 映射
                    replacements.remove(FunSimCtx.RegId.ACC)
                    result.add(op)
                }
                else -> result.add(op)
            }
        }
        
        return result
    }
}

/**
 * 死代码消除 Pass
 * 消除未使用的变量赋值
 */
object DeadCodeEliminationPass : OptimizationPass {
    override fun run(ops: List<IrOp>): List<IrOp> {
        return run(ops, emptySet())
    }
    
    /**
     * 运行死代码消除
     * @param ops 指令列表
     * @param liveOut 后续会使用的寄存器集合（如 Return 会使用 ACC）
     */
    fun run(ops: List<IrOp>, liveOut: Set<FunSimCtx.RegId>): List<IrOp> {
        // 反向扫描，维护活跃寄存器集合
        val liveRegisters = liveOut.toMutableSet()
        val result = mutableListOf<IrOp>()
        
        for (op in ops.reversed()) {
            when (op) {
                is IrOp.AssignReg -> {
                    // 如果左值在活跃集中，保留此指令
                    if (op.left in liveRegisters) {
                        liveRegisters.remove(op.left)
                        liveRegisters.addAll(op.right.read())
                        result.add(op)
                    }
                    // 否则删除此指令（死代码）
                }
                is IrOp.AssignObj -> {
                    // 对象赋值有副作用，必须保留
                    liveRegisters.addAll(op.read())
                    result.add(op)
                }
                is IrOp.Statement -> {
                    // 其他语句有副作用，必须保留
                    liveRegisters.addAll(op.read())
                    result.add(op)
                }
                else -> {
                    // 非语句节点（如标签等），保留
                    result.add(op)
                }
            }
        }
        
        result.reverse()
        return result
    }
}

/**
 * Return 优化 Pass
 * 将 _acc_ = xxx; return _acc_; 简化为 return xxx;
 */
object ReturnOptimizationPass : OptimizationPass {
    override fun run(ops: List<IrOp>): List<IrOp> {
        if (ops.size < 2) return ops
        
        val result = mutableListOf<IrOp>()
        var i = 0
        
        while (i < ops.size) {
            // 检查是否是 _acc_ = xxx; return _acc_; 模式
            if (i + 1 < ops.size &&
                ops[i] is IrOp.AssignReg &&
                (ops[i] as IrOp.AssignReg).left == FunSimCtx.RegId.ACC &&
                ops[i + 1] is IrOp.Return &&
                (ops[i + 1] as IrOp.Return).hasValue
            ) {
                // 合并为 return xxx;
                val assign = ops[i] as IrOp.AssignReg
                result.add(IrOp.Return.ReturnAcc)
                // 注意：这里简化了，实际上应该生成一个包含右值的 Return
                // 但由于 Return 是单例，我们保留原样
                result.add(ops[i])
                result.add(ops[i + 1])
                i += 2
            } else {
                result.add(ops[i])
                i++
            }
        }
        
        return result
    }
}

/**
 * 重复 Load 消除 Pass
 * 当同一对象的同一字段被连续读取且中间无写入时，将后续读取替换为对首次结果寄存器的引用
 */
object RedundantLoadEliminationPass : OptimizationPass {

    private sealed interface FieldKey {
        data class Name(val name: String) : FieldKey
        data class Index(val index: Int) : FieldKey
    }

    private data class CacheKey(val objReg: FunSimCtx.RegId, val field: FieldKey)

    override fun run(ops: List<IrOp>): List<IrOp> {
        val cache = mutableMapOf<CacheKey, FunSimCtx.RegId>()
        val result = mutableListOf<IrOp>()

        for (op in ops) {
            when (op) {
                is IrOp.AssignReg -> {
                    // 1. 尝试消除冗余字段读取
                    val newRight = eliminateRedundantLoad(op.right, cache)
                    result.add(IrOp.AssignReg(op.left, newRight))

                    // 2. 如果是首次出现的 ObjField.Name/Index(LoadReg, ...)，注册到缓存
                    if (newRight === op.right) {
                        registerLoad(op.right, op.left, cache)
                    }

                    // 3. left 寄存器被赋值，其作为 obj 的缓存项失效
                    cache.keys.removeAll { it.objReg == op.left }

                    // 4. 调用可能修改参数对象的属性
                    invalidateForSideEffects(op.right, cache)
                }
                is IrOp.AssignObj -> {
                    result.add(op)
                    invalidateForAssignObj(op, cache)
                }
                is IrOp.DeleteProp -> {
                    result.add(op)
                    for (reg in op.read()) {
                        cache.keys.removeAll { it.objReg == reg }
                    }
                }
                is IrOp.DefineGetterSetter -> {
                    result.add(op)
                    cache.keys.removeAll { it.objReg == op.obj }
                }
                else -> result.add(op)
            }
        }
        return result
    }

    private fun eliminateRedundantLoad(
        expr: IrOp.Expression,
        cache: Map<CacheKey, FunSimCtx.RegId>
    ): IrOp.Expression {
        return when (expr) {
            is IrOp.ObjField.Name -> {
                val obj = expr.obj
                if (obj is IrOp.LoadReg) {
                    val key = CacheKey(obj.regId, FieldKey.Name(expr.name))
                    cache[key]?.let { IrOp.LoadReg(it) } ?: expr
                } else expr
            }
            is IrOp.ObjField.Index -> {
                val obj = expr.obj
                if (obj is IrOp.LoadReg) {
                    val key = CacheKey(obj.regId, FieldKey.Index(expr.index))
                    cache[key]?.let { IrOp.LoadReg(it) } ?: expr
                } else expr
            }
            else -> expr
        }
    }

    private fun registerLoad(
        expr: IrOp.Expression,
        resultReg: FunSimCtx.RegId,
        cache: MutableMap<CacheKey, FunSimCtx.RegId>
    ) {
        when (expr) {
            is IrOp.ObjField.Name -> {
                val obj = expr.obj
                if (obj is IrOp.LoadReg) {
                    cache[CacheKey(obj.regId, FieldKey.Name(expr.name))] = resultReg
                }
            }
            is IrOp.ObjField.Index -> {
                val obj = expr.obj
                if (obj is IrOp.LoadReg) {
                    cache[CacheKey(obj.regId, FieldKey.Index(expr.index))] = resultReg
                }
            }
            // ObjField.Value — 动态 key，不缓存
            else -> {}
        }
    }

    private fun invalidateForAssignObj(
        op: IrOp.AssignObj,
        cache: MutableMap<CacheKey, FunSimCtx.RegId>
    ) {
        val objRegs = op.left.obj.read().toSet()
        when (op.left) {
            is IrOp.ObjField.Name -> {
                // 直接命名字段写入 — 仅失效该字段
                for (reg in objRegs) {
                    cache.remove(CacheKey(reg, FieldKey.Name((op.left as IrOp.ObjField.Name).name)))
                }
            }
            else -> {
                // Index/Value 写入 — 可能指向任意字段，失效 obj 的所有缓存
                for (reg in objRegs) {
                    cache.keys.removeAll { it.objReg == reg }
                }
            }
        }
    }

    private fun invalidateForSideEffects(
        expr: IrOp.Expression,
        cache: MutableMap<CacheKey, FunSimCtx.RegId>
    ) {
        val affectedRegs = mutableSetOf<FunSimCtx.RegId>()
        when (expr) {
            is IrOp.CallAcc -> {
                affectedRegs.addAll(expr.args)
                expr.overrideThis?.let { affectedRegs.add(it) }
            }
            is IrOp.CallWithTarget -> {
                affectedRegs.addAll(expr.args)
                expr.overrideThis?.let { affectedRegs.add(it) }
                affectedRegs.addAll(expr.target.read().toSet())
            }
            is IrOp.NewInst -> {
                affectedRegs.addAll(expr.constructorArgs)
                affectedRegs.add(expr.clazz)
            }
            is IrOp.NewClass -> {
                affectedRegs.add(expr.parent)
            }
            is IrOp.DynamicImport -> {
                affectedRegs.add(expr.regId)
            }
            else -> {} // 纯表达式 — 无副作用
        }
        for (reg in affectedRegs) {
            cache.keys.removeAll { it.objReg == reg }
        }
    }
}

/**
 * 表达式传播 Pass
 * 将 _acc_ = obj.method; _acc_ = this._acc_() 合并为 _acc_ = obj.method()
 * 以及更通用的表达式内联
 */
object ExpressionPropagationPass : OptimizationPass {
    /**
     * 表达式复杂度阈值
     * 超过此阈值的表达式不会被内联
     */
    private const val MAX_INLINE_COMPLEXITY = 5

    /**
     * 判断表达式是否是可调用的函数引用
     * 排除读取 ACC 的表达式（因为 ACC 在调用前会改变）
     */
    private fun isCallableExpression(expr: IrOp.Expression): Boolean {
        // 如果表达式读取 ACC，则不适合作为合并目标
        if (expr.read().contains(FunSimCtx.RegId.ACC)) return false
        return when (expr) {
            is IrOp.ObjField.Name -> {
                // 检查 obj 是否也不读取 ACC
                !expr.obj.read().contains(FunSimCtx.RegId.ACC)
            }
            is IrOp.ObjField.Index -> true      // obj[index]
            is IrOp.ObjField.Value -> true      // obj[value]
            is IrOp.LoadReg -> true             // variable
            is IrOp.DynamicImport -> true       // import()
            else -> false
        }
    }
    
    override fun run(ops: List<IrOp>): List<IrOp> {
        // 追踪每个寄存器的最近赋值表达式
        val exprMap = mutableMapOf<FunSimCtx.RegId, IrOp.Expression>()
        val result = mutableListOf<IrOp>()

        var prevInlinedRight: IrOp.Expression? = null
        var prevIndex: Int = -1

        for (op in ops) {
            when (op) {
                is IrOp.AssignReg -> {
                    // 尝试内联右值中的 LoadReg
                    val inlined = inlineExpression(op.right, exprMap)

                    // 检测函数调用合并模式：
                    // prev: _acc_ = X (X 是函数引用)
                    // curr: _acc_ = CallAcc(args, overrideThis)
                    val shouldMerge = op.left == FunSimCtx.RegId.ACC && op.right is IrOp.CallAcc &&
                        prevInlinedRight != null && isCallableExpression(prevInlinedRight)
                    if (shouldMerge) {
                        val call = op.right as IrOp.CallAcc
                        // 将前一条指令替换为 NOP（已合并到当前指令）
                        result[prevIndex] = IrOp.NOP
                        // 合并为 CallWithTarget
                        val merged = IrOp.CallWithTarget(prevInlinedRight, call.args, call.overrideThis)
                        result.add(IrOp.AssignReg(FunSimCtx.RegId.ACC, merged))
                        exprMap[FunSimCtx.RegId.ACC] = merged
                        prevInlinedRight = null
                        prevIndex = -1
                    } else {
                        result.add(IrOp.AssignReg(op.left, inlined))
                        exprMap[op.left] = inlined
                        prevInlinedRight = if (op.left == FunSimCtx.RegId.ACC) inlined else null
                        prevIndex = result.size - 1
                    }
                }
                is IrOp.AssignObj -> {
                    // 对象字段赋值只影响被写入的对象寄存器，不必清空全部映射
                    for (reg in op.effected()) {
                        exprMap.remove(reg)
                    }
                    result.add(op)
                    prevInlinedRight = null
                    prevIndex = -1
                }
                is IrOp.Statement -> {
                    // 其他语句可能有副作用，清除受影响的寄存器
                    for (reg in op.effected()) {
                        exprMap.remove(reg)
                    }
                    result.add(op)
                    prevInlinedRight = null
                    prevIndex = -1
                }
                else -> {
                    result.add(op)
                    prevInlinedRight = null
                    prevIndex = -1
                }
            }
        }

        return result
    }
    
    /**
     * 内联表达式中的 LoadReg
     */
    private fun inlineExpression(
        expr: IrOp.Expression,
        exprMap: Map<FunSimCtx.RegId, IrOp.Expression>,
        depth: Int = 0
    ): IrOp.Expression {
        if (depth > 50) return expr
        return when (expr) {
            is IrOp.LoadReg -> {
                val mapped = exprMap[expr.regId]
                if (mapped != null && expressionComplexity(mapped) <= MAX_INLINE_COMPLEXITY) {
                    // 内联表达式
                    inlineExpression(mapped, exprMap, depth + 1)
                } else {
                    expr
                }
            }
            is IrOp.ObjField.Name -> {
                // 内联 obj 表达式
                val inlinedObj = inlineExpression(expr.obj, exprMap, depth + 1)
                if (inlinedObj != expr.obj) {
                    IrOp.ObjField.Name(inlinedObj, expr.name)
                } else {
                    expr
                }
            }
            is IrOp.ObjField.Index -> {
                // 内联 obj 表达式
                val inlinedObj = inlineExpression(expr.obj, exprMap, depth + 1)
                if (inlinedObj != expr.obj) {
                    IrOp.ObjField.Index(inlinedObj, expr.index)
                } else {
                    expr
                }
            }
            is IrOp.ObjField.Value -> {
                // 内联 obj 和 value
                val inlinedObj = inlineExpression(expr.obj, exprMap, depth + 1)
                val newValue = resolveReg(expr.value, exprMap)
                if (inlinedObj != expr.obj || newValue != expr.value) {
                    IrOp.ObjField.Value(inlinedObj, newValue)
                } else {
                    expr
                }
            }
            is IrOp.CallAcc -> {
                // 内联 overrideThis
                val newThis = expr.overrideThis?.let { reg -> resolveReg(reg, exprMap) }
                // 内联 args
                val newArgs = expr.args.map { reg -> resolveReg(reg, exprMap) }
                if (newThis != expr.overrideThis || newArgs != expr.args) {
                    IrOp.CallAcc(newArgs, newThis)
                } else {
                    expr
                }
            }
            is IrOp.CallWithTarget -> {
                // 内联 target
                val newTarget = inlineExpression(expr.target, exprMap, depth + 1)
                // 内联 overrideThis
                val newThis = expr.overrideThis?.let { reg -> resolveReg(reg, exprMap) }
                // 内联 args
                val newArgs = expr.args.map { reg -> resolveReg(reg, exprMap) }
                if (newTarget != expr.target || newThis != expr.overrideThis || newArgs != expr.args) {
                    IrOp.CallWithTarget(newTarget, newArgs, newThis)
                } else {
                    expr
                }
            }
            is IrOp.NewInst -> {
                // 内联 clazz 和 constructorArgs
                val newClazz = resolveReg(expr.clazz, exprMap)
                val newArgs = expr.constructorArgs.map { reg -> resolveReg(reg, exprMap) }
                if (newClazz != expr.clazz || newArgs != expr.constructorArgs) {
                    IrOp.NewInst(newClazz, newArgs)
                } else {
                    expr
                }
            }
            is IrOp.DynamicImport -> {
                val newRegId = resolveReg(expr.regId, exprMap)
                if (newRegId != expr.regId) {
                    IrOp.DynamicImport(newRegId)
                } else {
                    expr
                }
            }
            is IrOp.NewClass -> {
                val newParent = resolveReg(expr.parent, exprMap)
                if (newParent != expr.parent) {
                    IrOp.NewClass(expr.constructor, expr.fields, newParent)
                } else {
                    expr
                }
            }
            is IrOp.BiExp -> {
                val newL = inlineExpression(expr.l, exprMap, depth + 1)
                val newR = inlineExpression(expr.r, exprMap, depth + 1)
                if (newL != expr.l || newR != expr.r) {
                    when (expr) {
                        is IrOp.BiExp.Add -> IrOp.BiExp.Add(newL, newR)
                        is IrOp.BiExp.Sub -> IrOp.BiExp.Sub(newL, newR)
                        is IrOp.BiExp.Mul -> IrOp.BiExp.Mul(newL, newR)
                        is IrOp.BiExp.Div -> IrOp.BiExp.Div(newL, newR)
                        is IrOp.BiExp.Mod -> IrOp.BiExp.Mod(newL, newR)
                        is IrOp.BiExp.Shl -> IrOp.BiExp.Shl(newL, newR)
                        is IrOp.BiExp.Shr -> IrOp.BiExp.Shr(newL, newR)
                        is IrOp.BiExp.AShr -> IrOp.BiExp.AShr(newL, newR)
                        is IrOp.BiExp.And -> IrOp.BiExp.And(newL, newR)
                        is IrOp.BiExp.Or -> IrOp.BiExp.Or(newL, newR)
                        is IrOp.BiExp.Xor -> IrOp.BiExp.Xor(newL, newR)
                        is IrOp.BiExp.Exp -> IrOp.BiExp.Exp(newL, newR)
                        is IrOp.BiExp.Eq -> IrOp.BiExp.Eq(newL, newR)
                        is IrOp.BiExp.NEq -> IrOp.BiExp.NEq(newL, newR)
                        is IrOp.BiExp.Less -> IrOp.BiExp.Less(newL, newR)
                        is IrOp.BiExp.LEq -> IrOp.BiExp.LEq(newL, newR)
                        is IrOp.BiExp.Ge -> IrOp.BiExp.Ge(newL, newR)
                        is IrOp.BiExp.GEq -> IrOp.BiExp.GEq(newL, newR)
                        is IrOp.BiExp.StrictEq -> IrOp.BiExp.StrictEq(newL, newR)
                        is IrOp.BiExp.StrictNEq -> IrOp.BiExp.StrictNEq(newL, newR)
                        is IrOp.BiExp.InstOf -> IrOp.BiExp.InstOf(newL, newR)
                        is IrOp.BiExp.IsIn -> IrOp.BiExp.IsIn(newL, newR)
                    }
                } else expr
            }
            is IrOp.UaExp -> {
                val newS = inlineExpression(expr.source, exprMap, depth + 1)
                if (newS != expr.source) {
                    when (expr) {
                        is IrOp.UaExp.Neg -> IrOp.UaExp.Neg(newS)
                        is IrOp.UaExp.Not -> IrOp.UaExp.Not(newS)
                        is IrOp.UaExp.Inc -> IrOp.UaExp.Inc(newS)
                        is IrOp.UaExp.Dec -> IrOp.UaExp.Dec(newS)
                        is IrOp.UaExp.IsTrue -> IrOp.UaExp.IsTrue(newS)
                        is IrOp.UaExp.IsFalse -> IrOp.UaExp.IsFalse(newS)
                        is IrOp.UaExp.TypeOf -> IrOp.UaExp.TypeOf(newS)
                        is IrOp.UaExp.ToNumber -> IrOp.UaExp.ToNumber(newS)
                        is IrOp.UaExp.ToNumeric -> IrOp.UaExp.ToNumeric(newS)
                        is IrOp.UaExp.GetTemplateObject -> IrOp.UaExp.GetTemplateObject(newS)
                        is IrOp.UaExp.GetAsyncIterator -> IrOp.UaExp.GetAsyncIterator(newS)
                        is IrOp.UaExp.GetIterator -> IrOp.UaExp.GetIterator(newS)
                        is IrOp.UaExp.GetPropIterator -> IrOp.UaExp.GetPropIterator(newS)
                        is IrOp.UaExp.GetNextPropName -> {
                            val newReg = (newS as? IrOp.LoadReg)?.regId ?: expr.iteratorReg
                            IrOp.UaExp.GetNextPropName(newReg)
                        }
                        is IrOp.UaExp.DeprecatedGetIteratorNext -> {
                            val newReg = (newS as? IrOp.LoadReg)?.regId ?: expr.iteratorReg
                            IrOp.UaExp.DeprecatedGetIteratorNext(newReg, expr.nextReg)
                        }
                    }
                } else expr
            }
            else -> expr
        }
    }
    
    /**
     * 解析寄存器引用，如果在 exprMap 中有对应的表达式且复杂度足够低，则内联
     */
    private fun resolveReg(
        reg: FunSimCtx.RegId,
        exprMap: Map<FunSimCtx.RegId, IrOp.Expression>,
        visited: MutableSet<FunSimCtx.RegId> = mutableSetOf()
    ): FunSimCtx.RegId {
        if (reg in visited) return reg  // 循环检测
        visited.add(reg)
        val mapped = exprMap[reg]
        if (mapped != null && expressionComplexity(mapped) <= MAX_INLINE_COMPLEXITY) {
            // 如果映射的表达式是 LoadReg，返回其 regId
            if (mapped is IrOp.LoadReg) {
                return resolveReg(mapped.regId, exprMap, visited)
            }
        }
        return reg
    }
    
    /**
     * 计算表达式的复杂度
     */
    private fun expressionComplexity(expr: IrOp.Expression): Int {
        return when (expr) {
            is IrOp.NoRegExpression -> 1
            is IrOp.LoadReg -> 1
            is IrOp.DynamicImport -> 1
            is IrOp.ObjField.Name -> 1 + expressionComplexity(expr.obj)
            is IrOp.ObjField.Index -> 1 + expressionComplexity(expr.obj)
            is IrOp.ObjField.Value -> 1 + expressionComplexity(expr.obj)
            is IrOp.CallAcc -> 3 + expr.args.size
            is IrOp.CallWithTarget -> 3 + expressionComplexity(expr.target) + expr.args.size
            is IrOp.NewInst -> 3 + expr.constructorArgs.size
            is IrOp.NewClass -> 4
            is IrOp.BiExp -> 1 + expressionComplexity(expr.l) + expressionComplexity(expr.r)
            is IrOp.UaExp -> 1 + expressionComplexity(expr.source)
            else -> 2
        }
    }
}
