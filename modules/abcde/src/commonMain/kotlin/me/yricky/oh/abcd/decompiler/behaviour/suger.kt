package me.yricky.oh.abcd.decompiler.behaviour

import me.yricky.oh.abcd.isa.Inst.Companion.toUnsignedInt

fun IrOp.Expression.st2Acc(): IrOp = IrOp.AssignReg(FunSimCtx.RegId.ACC, this)
fun JSValue.just(): IrOp.Expression = IrOp.JustImm(this)
fun FunSimCtx.RegId.ld(): IrOp.Expression = IrOp.LoadReg(this)
fun regId(opUnit: Number): FunSimCtx.RegId = FunSimCtx.RegId.regId(opUnit.toUnsignedInt())

val IrOp.assignLeftAcc: Boolean get() = (this is IrOp.Assign) && leftReg == FunSimCtx.RegId.ACC
val IrOp.assignLeftContainsAcc: Boolean get() = assignLeftAcc ||
        ((this as? IrOp.AssignObj)?.left?.effected()?.contains(FunSimCtx.RegId.ACC) == true) ||
        ((this as? IrOp.AssignObj)?.left?.read()?.contains(FunSimCtx.RegId.ACC) == true)
val IrOp.assignRightAcc: Boolean get() = (this is IrOp.Assign) && (right as? IrOp.LoadReg)?.regId == FunSimCtx.RegId.ACC
val IrOp.assignRightContainsAcc: Boolean get() = (this is IrOp.Assign) &&
        (right.effected().contains(FunSimCtx.RegId.ACC) || right.read().contains(FunSimCtx.RegId.ACC))

fun IrOp.Expression.replaceReg(reg: FunSimCtx.RegId, replaceTo: FunSimCtx.RegId): IrOp.Expression {
    if (reg == replaceTo) return this
    return when (this) {
        is IrOp.NoRegExpression -> this
        is IrOp.CallAcc -> IrOp.CallAcc(
            args.map { if (it == reg) replaceTo else it },
            overrideThis?.let { if (it == reg) replaceTo else it }
        )
        is IrOp.CallWithTarget -> IrOp.CallWithTarget(
            target.replaceReg(reg, replaceTo),
            args.map { if (it == reg) replaceTo else it },
            overrideThis?.let { if (it == reg) replaceTo else it }
        )
        is IrOp.DynamicImport -> if (regId == reg) IrOp.DynamicImport(replaceTo) else this
        is IrOp.LoadReg -> if (regId == reg) IrOp.LoadReg(replaceTo) else this
        is IrOp.NewClass -> if (parent == reg) IrOp.NewClass(constructor, fields, replaceTo) else this
        is IrOp.NewInst -> IrOp.NewInst(
            if (clazz == reg) replaceTo else clazz,
            constructorArgs.map { if (it == reg) replaceTo else it }
        )
        is IrOp.ObjField.Index -> if (obj is IrOp.LoadReg && obj.regId == reg) IrOp.ObjField.Index(IrOp.LoadReg(replaceTo), index) else this
        is IrOp.ObjField.Name -> if (obj is IrOp.LoadReg && obj.regId == reg) IrOp.ObjField.Name(IrOp.LoadReg(replaceTo), name) else this
        is IrOp.ObjField.Value -> {
            val objLoadReg = obj as? IrOp.LoadReg
            if (objLoadReg != null && (objLoadReg.regId == reg || value == reg)) {
                IrOp.ObjField.Value(
                    if (objLoadReg.regId == reg) IrOp.LoadReg(replaceTo) else obj,
                    if (value == reg) replaceTo else value
                )
            } else this
        }
        // Binary expressions
        is IrOp.BiExp.AShr -> IrOp.BiExp.AShr(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.Add -> IrOp.BiExp.Add(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.And -> IrOp.BiExp.And(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.Div -> IrOp.BiExp.Div(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.Eq -> IrOp.BiExp.Eq(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.Exp -> IrOp.BiExp.Exp(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.GEq -> IrOp.BiExp.GEq(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.Ge -> IrOp.BiExp.Ge(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.InstOf -> IrOp.BiExp.InstOf(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.IsIn -> IrOp.BiExp.IsIn(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.LEq -> IrOp.BiExp.LEq(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.Less -> IrOp.BiExp.Less(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.Mod -> IrOp.BiExp.Mod(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.Mul -> IrOp.BiExp.Mul(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.NEq -> IrOp.BiExp.NEq(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.Or -> IrOp.BiExp.Or(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.Shl -> IrOp.BiExp.Shl(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.Shr -> IrOp.BiExp.Shr(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.StrictEq -> IrOp.BiExp.StrictEq(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.StrictNEq -> IrOp.BiExp.StrictNEq(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.Sub -> IrOp.BiExp.Sub(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        is IrOp.BiExp.Xor -> IrOp.BiExp.Xor(l.replaceReg(reg, replaceTo), r.replaceReg(reg, replaceTo))
        // Unary expressions
        is IrOp.UaExp.Dec -> IrOp.UaExp.Dec(source.replaceReg(reg, replaceTo))
        is IrOp.UaExp.GetTemplateObject -> IrOp.UaExp.GetTemplateObject(source.replaceReg(reg, replaceTo))
        is IrOp.UaExp.Inc -> IrOp.UaExp.Inc(source.replaceReg(reg, replaceTo))
        is IrOp.UaExp.IsFalse -> IrOp.UaExp.IsFalse(source.replaceReg(reg, replaceTo))
        is IrOp.UaExp.IsTrue -> IrOp.UaExp.IsTrue(source.replaceReg(reg, replaceTo))
        is IrOp.UaExp.Neg -> IrOp.UaExp.Neg(source.replaceReg(reg, replaceTo))
        is IrOp.UaExp.Not -> IrOp.UaExp.Not(source.replaceReg(reg, replaceTo))
        is IrOp.UaExp.ToNumber -> IrOp.UaExp.ToNumber(source.replaceReg(reg, replaceTo))
        is IrOp.UaExp.ToNumeric -> IrOp.UaExp.ToNumeric(source.replaceReg(reg, replaceTo))
        is IrOp.UaExp.TypeOf -> IrOp.UaExp.TypeOf(source.replaceReg(reg, replaceTo))
        is IrOp.UaExp.GetAsyncIterator -> IrOp.UaExp.GetAsyncIterator(source.replaceReg(reg, replaceTo))
    }
}