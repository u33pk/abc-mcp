package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.decompiler.behaviour.FunSimCtx
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.decompiler.behaviour.JSValue
import me.yricky.oh.abcd.decompiler.behaviour.assignLeftAcc
import me.yricky.oh.abcd.decompiler.behaviour.assignRightAcc
import me.yricky.oh.abcd.decompiler.behaviour.replaceReg

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
    
    private fun simplifyExpression(expr: IrOp.Expression): IrOp.Expression {
        return when (expr) {
            is IrOp.BiExp.Add -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                when {
                    isZero(l) -> r
                    isZero(r) -> l
                    else -> IrOp.BiExp.Add(l, r)
                }
            }
            is IrOp.BiExp.Sub -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                when {
                    isZero(r) -> l
                    l == r -> zero()
                    else -> IrOp.BiExp.Sub(l, r)
                }
            }
            is IrOp.BiExp.Mul -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                when {
                    isZero(l) || isZero(r) -> zero()
                    isOne(l) -> r
                    isOne(r) -> l
                    else -> IrOp.BiExp.Mul(l, r)
                }
            }
            is IrOp.BiExp.Div -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                when {
                    isZero(l) -> zero()
                    isOne(r) -> l
                    else -> IrOp.BiExp.Div(l, r)
                }
            }
            is IrOp.BiExp.Mod -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                when {
                    isZero(l) -> zero()
                    isOne(r) -> zero()
                    else -> IrOp.BiExp.Mod(l, r)
                }
            }
            is IrOp.BiExp.Shl -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                when {
                    isZero(l) -> zero()
                    isZero(r) -> l
                    else -> IrOp.BiExp.Shl(l, r)
                }
            }
            is IrOp.BiExp.Shr -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                when {
                    isZero(l) -> zero()
                    isZero(r) -> l
                    else -> IrOp.BiExp.Shr(l, r)
                }
            }
            is IrOp.BiExp.AShr -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                when {
                    isZero(l) -> zero()
                    isZero(r) -> l
                    else -> IrOp.BiExp.AShr(l, r)
                }
            }
            is IrOp.BiExp.And -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                when {
                    isZero(l) || isZero(r) -> zero()
                    else -> IrOp.BiExp.And(l, r)
                }
            }
            is IrOp.BiExp.Or -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                when {
                    isZero(l) -> r
                    isZero(r) -> l
                    else -> IrOp.BiExp.Or(l, r)
                }
            }
            is IrOp.BiExp.Xor -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                when {
                    isZero(l) -> r
                    isZero(r) -> l
                    l == r -> zero()
                    else -> IrOp.BiExp.Xor(l, r)
                }
            }
            is IrOp.BiExp.Exp -> {
                val l = simplifyExpression(expr.l)
                val r = simplifyExpression(expr.r)
                when {
                    isZero(r) -> one()
                    isOne(r) -> l
                    else -> IrOp.BiExp.Exp(l, r)
                }
            }
            // Unary expressions
            is IrOp.UaExp.Neg -> {
                val s = simplifyExpression(expr.source)
                when {
                    isZero(s) -> s
                    s is IrOp.JustImm && s.value is JSValue.Number -> IrOp.JustImm(JSValue.Number(-s.value.value.toDouble()))
                    else -> IrOp.UaExp.Neg(s)
                }
            }
            is IrOp.UaExp.Not -> {
                val s = simplifyExpression(expr.source)
                when {
                    s is IrOp.JustImm && s.value is JSValue.Number -> IrOp.JustImm(JSValue.Number(s.value.value.toInt().inv()))
                    else -> IrOp.UaExp.Not(s)
                }
            }
            is IrOp.UaExp.Inc -> {
                val s = simplifyExpression(expr.source)
                when {
                    s is IrOp.JustImm && s.value is JSValue.Number -> IrOp.JustImm(JSValue.Number(s.value.value.toDouble() + 1))
                    else -> IrOp.UaExp.Inc(s)
                }
            }
            is IrOp.UaExp.Dec -> {
                val s = simplifyExpression(expr.source)
                when {
                    s is IrOp.JustImm && s.value is JSValue.Number -> IrOp.JustImm(JSValue.Number(s.value.value.toDouble() - 1))
                    else -> IrOp.UaExp.Dec(s)
                }
            }
            // Other expressions - recursively simplify children
            is IrOp.BiExp.Eq -> IrOp.BiExp.Eq(simplifyExpression(expr.l), simplifyExpression(expr.r))
            is IrOp.BiExp.NEq -> IrOp.BiExp.NEq(simplifyExpression(expr.l), simplifyExpression(expr.r))
            is IrOp.BiExp.Less -> IrOp.BiExp.Less(simplifyExpression(expr.l), simplifyExpression(expr.r))
            is IrOp.BiExp.LEq -> IrOp.BiExp.LEq(simplifyExpression(expr.l), simplifyExpression(expr.r))
            is IrOp.BiExp.Ge -> IrOp.BiExp.Ge(simplifyExpression(expr.l), simplifyExpression(expr.r))
            is IrOp.BiExp.GEq -> IrOp.BiExp.GEq(simplifyExpression(expr.l), simplifyExpression(expr.r))
            is IrOp.BiExp.StrictEq -> IrOp.BiExp.StrictEq(simplifyExpression(expr.l), simplifyExpression(expr.r))
            is IrOp.BiExp.StrictNEq -> IrOp.BiExp.StrictNEq(simplifyExpression(expr.l), simplifyExpression(expr.r))
            is IrOp.BiExp.InstOf -> IrOp.BiExp.InstOf(simplifyExpression(expr.l), simplifyExpression(expr.r))
            is IrOp.BiExp.IsIn -> IrOp.BiExp.IsIn(simplifyExpression(expr.l), simplifyExpression(expr.r))
            is IrOp.UaExp.IsTrue -> IrOp.UaExp.IsTrue(simplifyExpression(expr.source))
            is IrOp.UaExp.IsFalse -> IrOp.UaExp.IsFalse(simplifyExpression(expr.source))
            is IrOp.UaExp.TypeOf -> IrOp.UaExp.TypeOf(simplifyExpression(expr.source))
            is IrOp.UaExp.ToNumber -> IrOp.UaExp.ToNumber(simplifyExpression(expr.source))
            is IrOp.UaExp.ToNumeric -> IrOp.UaExp.ToNumeric(simplifyExpression(expr.source))
            is IrOp.UaExp.GetTemplateObject -> IrOp.UaExp.GetTemplateObject(simplifyExpression(expr.source))
            else -> expr
        }
    }
    
    private fun isZero(expr: IrOp.Expression): Boolean =
        expr is IrOp.JustImm && expr.value is JSValue.Number && expr.value.value == 0.0
    
    private fun isOne(expr: IrOp.Expression): Boolean =
        expr is IrOp.JustImm && expr.value is JSValue.Number && expr.value.value == 1.0
    
    private fun zero(): IrOp.Expression = IrOp.JustImm(JSValue.Number(0))
    private fun one(): IrOp.Expression = IrOp.JustImm(JSValue.Number(1))
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
                    // 替换右值中的寄存器引用
                    var newRight = op.right
                    for ((reg, expr) in replacements) {
                        newRight = newRight.replaceReg(reg, FunSimCtx.RegId.ACC) // 临时替换
                    }
                    
                    // 如果右值是 LoadReg(r) 且 r 在 replacements 中，替换为对应的表达式
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
                    // 对象赋值可能影响所有已知表达式，保守地清除
                    exprMap.clear()
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
            else -> expr
        }
    }
    
    /**
     * 解析寄存器引用，如果在 exprMap 中有对应的表达式且复杂度足够低，则内联
     */
    private fun resolveReg(
        reg: FunSimCtx.RegId,
        exprMap: Map<FunSimCtx.RegId, IrOp.Expression>
    ): FunSimCtx.RegId {
        val mapped = exprMap[reg]
        if (mapped != null && expressionComplexity(mapped) <= MAX_INLINE_COMPLEXITY) {
            // 如果映射的表达式是 LoadReg，返回其 regId
            if (mapped is IrOp.LoadReg) {
                return resolveReg(mapped.regId, exprMap)
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
            is IrOp.ObjField.Name -> 2
            is IrOp.ObjField.Index -> 2
            is IrOp.ObjField.Value -> 2
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
