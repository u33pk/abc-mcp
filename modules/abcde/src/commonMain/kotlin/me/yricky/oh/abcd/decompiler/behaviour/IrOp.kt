package me.yricky.oh.abcd.decompiler.behaviour

import me.yricky.oh.abcd.isa.Asm.AsmItem
import me.yricky.oh.abcd.isa.Inst.Companion.toUnsignedInt
import me.yricky.oh.abcd.isa.InstFmt
import me.yricky.oh.abcd.literal.LiteralArray
import me.yricky.oh.abcd.literal.ModuleLiteralArray
import me.yricky.oh.abcd.literal.OhmUrl

sealed interface IrOp {
    companion object {
        fun from(item: AsmItem): IrOp {
            val opCode = item.ins.opCode
            item.prefix?.let { prefix ->
                return when(prefix){
                    0xfb.toByte() -> when(opCode){
                        0x01.toByte() -> AssignObj(ObjField.Value(regId(item.opUnits[4]), regId(item.opUnits[3])), LoadReg.acc)
                        0x02.toByte() -> AssignObj(ObjField.Index(regId(item.opUnits[4]),item.opUnits[3].toUnsignedInt()), LoadReg.acc)

                        0x09.toByte() -> LoadExternalModule(item.asm.code.method.clazz!!.moduleInfo!!.regularImports[item.opUnits[2].toUnsignedInt()]).st2Acc()

                        0x13.toByte() -> UaExp.IsTrue(LoadReg.acc).st2Acc()
                        0x14.toByte() -> UaExp.IsFalse(LoadReg.acc).st2Acc()
                        else -> UnImplemented(item)
                    }
                    0xfc.toByte() -> when(opCode){
                        // deprecated.poplexenv：与 poplexenv 语义相同，弹出当前词法环境
                        0x01.toByte() -> PopLexEnv
                        // deprecated.getiteratornext v1, v2：旧版同步迭代器 next
                        0x02.toByte() -> UaExp.DeprecatedGetIteratorNext(
                            regId(item.opUnits[2].toUnsignedInt()),
                            regId(item.opUnits[3].toUnsignedInt())
                        ).st2Acc()
                        else -> Deprecated
                    }
                    0xfd.toByte() -> when(opCode){
                        0x0a.toByte() -> AssignObj(ObjField.Index(regId(item.opUnits[2]), item.opUnits[3].toUnsignedInt()), LoadReg.acc)
                        0x0b.toByte() -> CopyRestArgs((item.opUnits[2] as Short).toUShort().toInt()).st2Acc()
                        0x0f.toByte() -> {
                            val local = item.asm.code.method.clazz!!.moduleInfo!!.localExports[item.opUnits[2].toUnsignedInt()]
                            AssignModuleVar(local, LoadReg.acc)
                        }
                        0x10.toByte() -> {
                            val local = item.asm.code.method.clazz!!.moduleInfo!!.localExports[item.opUnits[2].toUnsignedInt()]
                            LoadLocalModuleVar(local).st2Acc()
                        }
                        0x11.toByte() -> LoadExternalModule(item.asm.code.method.clazz!!.moduleInfo!!.regularImports[item.opUnits[2].toUnsignedInt()]).st2Acc()

                        // wide.callrange: imm16 高字节=arg count, 低字节=start reg
                        0x04.toByte() -> {
                            val imm = item.opUnits[2].toUnsignedInt()
                            val argCount = imm shr 8
                            val firstArg = imm and 0xFF
                            CallAcc((0 until argCount).map { regId(firstArg + it) }).st2Acc()
                        }
                        // wide.callthisrange: imm16 高字节=this reg, 低字节=arg count; v=first arg reg
                        0x05.toByte() -> {
                            val imm = item.opUnits[2].toUnsignedInt()
                            val thisReg = imm shr 8
                            val argCount = imm and 0xFF
                            val firstArg = item.opUnits[3].toUnsignedInt()
                            CallAcc((0 until argCount).map { regId(firstArg + it) }, regId(thisReg)).st2Acc()
                        }
                        // wide.supercallthisrange: 同 wide.callthisrange
                        0x06.toByte() -> {
                            val imm = item.opUnits[2].toUnsignedInt()
                            val thisReg = imm shr 8
                            val argCount = imm and 0xFF
                            val firstArg = item.opUnits[3].toUnsignedInt()
                            CallAcc((0 until argCount).map { regId(firstArg + it) }, regId(thisReg)).st2Acc()
                        }
                        // wide.supercallarrowrange: 同 wide.callthisrange
                        0x07.toByte() -> {
                            val imm = item.opUnits[2].toUnsignedInt()
                            val thisReg = imm shr 8
                            val argCount = imm and 0xFF
                            val firstArg = item.opUnits[3].toUnsignedInt()
                            CallAcc((0 until argCount).map { regId(firstArg + it) }, regId(thisReg)).st2Acc()
                        }

                        // wide.newlexenv imm:u16
                        0x02.toByte() -> NewLexEnv(item.opUnits[2].toUnsignedInt())
                        // wide.newlexenvwithname imm:u16, literalarray_id
                        0x03.toByte() -> NewLexEnvWithName(item.opUnits[2].toUnsignedInt(), parseLexEnvNames(item))

                        else -> UnImplemented(item)
                    }
                    0xfe.toByte() -> when(opCode){
                        0x00.toByte() -> Throw.Acc
                        0x01.toByte() -> Throw.Error("notexists")
                        0x02.toByte() -> Throw.Error("patternnoncoercible")
                        0x03.toByte() -> Throw.Error("deletesuperproperty")
                        0x04.toByte() -> Throw.Error("constassignment", "${item.ins.format[2]}")
                        // throw.ifnotobject：for-of 中校验 next 结果为对象，运行时检查，按 NOP 处理
                        0x05.toByte() -> NOP

                        // throw.ifsupernotcorrectcall：方舟编译器在 super() / super.method() 调用前插入的运行时校验。
                        // 正常代码不会触发，无对应 TS/ArkTS 语法，按编译器 bookkeeping 处理为 NOP，避免污染反编译输出。
                        0x07.toByte() -> NOP
                        0x08.toByte() -> NOP

                        0x09.toByte() -> JustAnno("acc: ${(item.ins.format[2] as InstFmt.SId).getString(item)}")
                        else -> UnImplemented(item)
                    }
                    else -> UnImplemented(item)
                }
            }

            return when(opCode){
                0x00.toByte() -> JSValue.Undefined.just().st2Acc()
                0x01.toByte() -> JSValue.Null.just().st2Acc()
                0x02.toByte() -> JSValue.True.just().st2Acc()
                0x03.toByte() -> JSValue.False.just().st2Acc()
                0x04.toByte() -> JSValue.ObjInst(emptyMap()).just().st2Acc()
                0x05.toByte() -> ArrayLiteral(emptyList()).st2Acc()
                0x06.toByte() -> ArrayLiteral(
                    JSValue.asArr(item.asm,(item.ins.format[2] as InstFmt.LId).getLA(item)).content.map { ArrayElement.Expr(it.just()) }
                ).st2Acc()
                0x07.toByte() -> JSValue.asObj(item.asm,(item.ins.format[2] as InstFmt.LId).getLA(item)).just().st2Acc()
                0x08.toByte() -> {
                    val clazz = item.opUnits[3].toUnsignedInt()
                    val argC = item.opUnits[2].toUnsignedInt()
                    val args = if(argC.toUnsignedInt() > 1) ((clazz + 1) until (clazz + argC)) else emptyList()
                    AssignReg(FunSimCtx.RegId.ACC, NewInst(regId(clazz), args.map { regId(it) }))
                }
                0x09.toByte() -> NewLexEnv(item.opUnits[1].toUnsignedInt())
                0x0a.toByte() -> BiExp.Add(regId(item.opUnits[2]).ld(), LoadReg.acc).st2Acc()
                0x0b.toByte() -> BiExp.Sub(regId(item.opUnits[2]).ld(), LoadReg.acc).st2Acc()
                0x0c.toByte() -> BiExp.Mul(regId(item.opUnits[2]).ld(), LoadReg.acc).st2Acc()
                0x0d.toByte() -> BiExp.Div(regId(item.opUnits[2]).ld(), LoadReg.acc).st2Acc()
                0x0e.toByte() -> BiExp.Mod(regId(item.opUnits[2]).ld(), LoadReg.acc).st2Acc()
                0x0f.toByte() -> BiExp.Eq(regId(item.opUnits[2]).ld(), LoadReg.acc).st2Acc()
                0x10.toByte() -> BiExp.NEq(regId(item.opUnits[2]).ld(), LoadReg.acc).st2Acc()
                0x11.toByte() -> BiExp.Less(regId(item.opUnits[2]).ld(), LoadReg.acc).st2Acc()
                0x12.toByte() -> BiExp.LEq(regId(item.opUnits[2]).ld(), LoadReg.acc).st2Acc()
                0x13.toByte() -> BiExp.Ge(regId(item.opUnits[2]).ld(), LoadReg.acc).st2Acc()
                0x14.toByte() -> BiExp.GEq(regId(item.opUnits[2]).ld(), LoadReg.acc).st2Acc()
                0x15.toByte() -> BiExp.Shl(regId(item.opUnits[2]).ld(), LoadReg.acc).st2Acc()
                0x16.toByte() -> BiExp.Shr(regId(item.opUnits[2]).ld(), LoadReg.acc).st2Acc()
                0x17.toByte() -> BiExp.AShr(regId(item.opUnits[2]).ld(), LoadReg.acc).st2Acc()
                0x18.toByte() -> BiExp.And(regId(item.opUnits[2]).ld(), LoadReg.acc).st2Acc()
                0x19.toByte() -> BiExp.Or(regId(item.opUnits[2]).ld(), LoadReg.acc).st2Acc()
                0x1a.toByte() -> BiExp.Xor(regId(item.opUnits[2]).ld(), LoadReg.acc).st2Acc()
                0x1b.toByte() -> BiExp.Exp(regId(item.opUnits[2]).ld(), LoadReg.acc).st2Acc()
                0x1c.toByte() -> UaExp.TypeOf(LoadReg.acc).st2Acc()
                0x1d.toByte() -> UaExp.ToNumber(LoadReg.acc).st2Acc()
                0x1e.toByte() -> UaExp.ToNumeric(LoadReg.acc).st2Acc()
                0x1f.toByte() -> UaExp.Neg(LoadReg.acc).st2Acc()
                0x20.toByte() -> UaExp.Not(LoadReg.acc).st2Acc()
                0x21.toByte() -> UaExp.Inc(LoadReg.acc).st2Acc()
                0x22.toByte() -> UaExp.Dec(LoadReg.acc).st2Acc()
                0x23.toByte() -> UaExp.IsTrue(LoadReg.acc).st2Acc()
                0x24.toByte() -> UaExp.IsFalse(LoadReg.acc).st2Acc()
                0x25.toByte() -> BiExp.IsIn(LoadReg(regId(item.opUnits[2])), LoadReg(FunSimCtx.RegId.ACC)).st2Acc()
                0x26.toByte() -> BiExp.InstOf(LoadReg(regId(item.opUnits[2])), LoadReg(FunSimCtx.RegId.ACC)).st2Acc()
                0x27.toByte() -> BiExp.StrictNEq(LoadReg(FunSimCtx.RegId.ACC), LoadReg(regId(item.opUnits[2]))).st2Acc()
                0x28.toByte() -> BiExp.StrictEq(LoadReg(FunSimCtx.RegId.ACC), LoadReg(regId(item.opUnits[2]))).st2Acc()
                0x29.toByte() -> CallAcc().st2Acc()
                0x2a.toByte() -> CallAcc(listOf(regId(item.opUnits[2]))).st2Acc()
                0x2b.toByte() -> CallAcc(listOf(regId(item.opUnits[2]), regId(item.opUnits[3].toUnsignedInt()))).st2Acc()
                0x2c.toByte() -> CallAcc(listOf(
                    regId(item.opUnits[2]),
                    regId(item.opUnits[3].toUnsignedInt()),
                    regId(item.opUnits[4].toUnsignedInt())
                )).st2Acc()
                0x2d.toByte() -> CallAcc(overrideThis = regId(item.opUnits[2])).st2Acc()
                0x2e.toByte() -> CallAcc(listOf(regId(item.opUnits[3])), regId(item.opUnits[2])).st2Acc()
                0x2f.toByte() -> CallAcc(listOf(
                    regId(item.opUnits[3]),
                    regId(item.opUnits[4].toUnsignedInt())
                ), regId(item.opUnits[2])).st2Acc()
                0x30.toByte() -> CallAcc(listOf(
                    regId(item.opUnits[3]),
                    regId(item.opUnits[4].toUnsignedInt()),
                    regId(item.opUnits[5].toUnsignedInt())
                ), regId(item.opUnits[2])).st2Acc()

                // callthisrange: this.method(arg0, arg1, ...)
                //   opUnits[1] = this reg, opUnits[2] = arg count, opUnits[3] = first arg reg
                0x31.toByte() -> {
                    val thisReg = item.opUnits[1].toUnsignedInt()
                    val argCount = item.opUnits[2].toUnsignedInt()
                    val firstArg = item.opUnits[3].toUnsignedInt()
                    CallAcc((0 until argCount).map { regId(firstArg + it) }, regId(thisReg)).st2Acc()
                }
                // supercallthisrange: super(arg0, arg1, ...)
                //   opUnits[1] = this reg, opUnits[2] = arg count, opUnits[3] = first arg reg
                0x32.toByte() -> {
                    val thisReg = item.opUnits[1].toUnsignedInt()
                    val argCount = item.opUnits[2].toUnsignedInt()
                    val firstArg = item.opUnits[3].toUnsignedInt()
                    CallAcc((0 until argCount).map { regId(firstArg + it) }, regId(thisReg)).st2Acc()
                }

                0x33.toByte() -> JSValue.Function(
                    (item.ins.format[2] as InstFmt.MId).getMethod(item),
                    item.opUnits[3].toUnsignedInt()
                ).just().st2Acc()

                // definemethod: 注册方法到类，ACC 透传（acc=inout:top）
                0x34.toByte() -> NOP

                0x35.toByte() -> NewClass(
                    JSValue.Function((item.ins.format[2] as InstFmt.MId).getMethod(item), item.opUnits[4].toUnsignedInt()),
                    (item.ins.format[3] as InstFmt.LId).getLA(item),
                    regId(item.opUnits[5])
                ).st2Acc()

                0x37.toByte() -> AssignReg(LoadReg.ACC,ObjField.Value(regId(item.opUnits[2]), LoadReg.ACC))
                0x38.toByte() -> AssignObj(ObjField.Value(regId(item.opUnits[2]), regId(item.opUnits[3])), LoadReg.acc)

                0x3a.toByte() -> AssignReg(LoadReg.ACC, ObjField.Index(LoadReg.ACC,item.opUnits[2].toUnsignedInt()))
                0x3b.toByte() -> AssignObj(ObjField.Index(regId(item.opUnits[2]), item.opUnits[3].toUnsignedInt()), LoadReg.acc)
                0x3c.toByte() -> LoadReg(FunSimCtx.RegId.lexId(item.opUnits[1].toUnsignedInt(),item.opUnits[2].toUnsignedInt())).st2Acc()
                0x3d.toByte() -> AssignReg(FunSimCtx.RegId.lexId(item.opUnits[1].toUnsignedInt(),item.opUnits[2].toUnsignedInt()), LoadReg.acc)
                0x3e.toByte() -> JSValue.Str((item.ins.format[1] as InstFmt.SId).getString(item)).just().st2Acc()
                0x3f.toByte() -> ObjField.Name(FunSimCtx.RegId.GLOBAL, (item.ins.format[2] as InstFmt.SId).getString(item)).st2Acc()
                0x40.toByte() -> AssignObj(ObjField.Name(FunSimCtx.RegId.GLOBAL, (item.ins.format[2] as InstFmt.SId).getString(item)),LoadReg.acc)
                0x41.toByte() -> ObjField.Name(FunSimCtx.RegId.GLOBAL, (item.ins.format[2] as InstFmt.SId).getString(item)).st2Acc()
                0x42.toByte() -> ObjField.Name(LoadReg.ACC, (item.ins.format[2] as InstFmt.SId).getString(item)).st2Acc()
                0x43.toByte() -> AssignObj(ObjField.Name(regId(item.opUnits[3]),(item.ins.format[2] as InstFmt.SId).getString(item)), LoadReg.acc)
                0x44.toByte() -> AssignReg(regId(item.opUnits[1]), regId(item.opUnits[2]).ld())
                0x45.toByte() -> AssignReg(regId(item.opUnits[1]), regId(item.opUnits[2]).ld())

                0x47.toByte() -> AssignObj(ObjField.Name(FunSimCtx.RegId.GLOBAL, (item.ins.format[2] as InstFmt.SId).getString(item)), LoadReg.acc) // stconsttoglobalrecord
                0x48.toByte() -> AssignObj(ObjField.Name(FunSimCtx.RegId.GLOBAL, (item.ins.format[2] as InstFmt.SId).getString(item)), LoadReg.acc) // sttoglobalrecord

                0x49.toByte() -> ObjField.Name(
                    FunSimCtx.RegId.THIS,
                    (item.ins.format[2] as InstFmt.SId).getString(item)
                ).st2Acc()
                0x4a.toByte() -> AssignObj(ObjField.Name(regId(item.opUnits[3]),(item.ins.format[2] as InstFmt.SId).getString(item)), FunSimCtx.RegId.THIS.ld())
                0x4b.toByte() -> ObjField.Value(FunSimCtx.RegId.THIS, LoadReg.ACC).st2Acc()
                0x4c.toByte() -> AssignObj(ObjField.Value(FunSimCtx.RegId.THIS, regId(item.opUnits[3])), LoadReg.acc)
                0x4d.toByte() -> Jump(item.opUnits[1].toInt())
                0x4e.toByte() -> Jump(item.opUnits[1].toInt())
                0x4f.toByte() -> JumpIf(item.opUnits[1].toInt(), BiExp.Eq(LoadReg.acc, JSValue.Number(0).just()))
                0x50.toByte() -> JumpIf(item.opUnits[1].toInt(), BiExp.Eq(LoadReg.acc, JSValue.Number(0).just()))
                0x51.toByte() -> JumpIf(item.opUnits[1].toInt(), BiExp.NEq(LoadReg.acc, JSValue.Number(0).just()))
                in (0x52.toByte() .. 0x5f.toByte()) -> Disabled
                0x60.toByte() -> AssignReg(LoadReg.ACC, regId(item.opUnits[1]).ld())
                0x61.toByte() -> AssignReg(regId(item.opUnits[1]), LoadReg.acc)
                0x62.toByte() -> AssignReg(LoadReg.ACC, JustImm(JSValue.Number(item.opUnits[1])))
                0x63.toByte() -> AssignReg(LoadReg.ACC, JustImm(JSValue.Number(Double.fromBits(item.opUnits[1] as Long))))
                0x64.toByte() -> Return.ReturnAcc
                0x65.toByte() -> Return.ReturnUndefined

                // iterator instructions
                0x36.toByte() -> UaExp.GetNextPropName(regId(item.opUnits[1].toUnsignedInt())).st2Acc()
                0x66.toByte() -> UaExp.GetPropIterator(LoadReg.acc).st2Acc()
                0x67.toByte() -> UaExp.GetIterator(LoadReg.acc).st2Acc()
                0x68.toByte() -> CloseIterator(regId(item.opUnits[2].toUnsignedInt()))
                0xab.toByte() -> UaExp.GetIterator(LoadReg.acc).st2Acc()
                0xac.toByte() -> CloseIterator(regId(item.opUnits[2].toUnsignedInt()))

                0x69.toByte() -> PopLexEnv

                0x6a.toByte() -> JSValue.Nan.just().st2Acc()
                0x6b.toByte() -> JSValue.Infinity.just().st2Acc()
                0x6c.toByte() -> LoadReg(FunSimCtx.RegId.ARGUMENTS).st2Acc()
                0x6d.toByte() -> LoadReg(FunSimCtx.RegId.GLOBAL).st2Acc()
                0x6e.toByte() -> Disabled
                0x6f.toByte() -> LoadReg(FunSimCtx.RegId.THIS).st2Acc()
                0x70.toByte() -> JSValue.Hole.just().st2Acc()

                // callrange: func(arg0, arg1, ...) — 无 this 绑定
                //   opUnits[1] = IC slot, opUnits[2] = arg count, opUnits[3] = first arg reg
                0x73.toByte() -> {
                    val argCount = item.opUnits[2].toUnsignedInt()
                    val firstArg = item.opUnits[3].toUnsignedInt()
                    CallAcc((0 until argCount).map { regId(firstArg + it) }).st2Acc()
                }

                0x74.toByte() -> JSValue.Function(
                    (item.ins.format[2] as InstFmt.MId).getMethod(item),
                    item.opUnits[3].toUnsignedInt()
                ).just().st2Acc()
                0x75.toByte() -> NewClass(
                    JSValue.Function((item.ins.format[2] as InstFmt.MId).getMethod(item), item.opUnits[4].toUnsignedInt()),
                    (item.ins.format[3] as InstFmt.LId).getLA(item),
                    regId(item.opUnits[5])
                ).st2Acc()
                0x76.toByte() -> UaExp.GetTemplateObject(LoadReg.acc).st2Acc()
                0x77.toByte() -> AssignObj(ObjField.Name(LoadReg.ACC, JSValue.PROTO), regId(item.opUnits[2]).ld())
                0x78.toByte() -> AssignObj(ObjField.Value(regId(item.opUnits[2]), regId(item.opUnits[3])), LoadReg.acc)
                0x79.toByte() -> AssignObj(ObjField.Index(regId(item.opUnits[2]),item.opUnits[3].toUnsignedInt()), LoadReg.acc)
                0x7a.toByte() -> AssignObj(
                    ObjField.Name(regId(item.opUnits[3]),(item.ins.format[2] as InstFmt.SId).getString(item)),
                    LoadReg.acc
                )
                0x7b.toByte() -> GetModuleNamespace(item.asm.code.method.clazz!!.moduleInfo!!.moduleRequests[item.opUnits[1].toUnsignedInt()]).st2Acc()

                0x7c.toByte() -> {
                    val local = item.asm.code.method.clazz!!.moduleInfo!!.localExports[item.opUnits[1].toUnsignedInt()]
                    AssignModuleVar(local, LoadReg.acc)
                }
                0x7d.toByte() -> {
                    val local = item.asm.code.method.clazz!!.moduleInfo!!.localExports[item.opUnits[1].toUnsignedInt()]
                    LoadLocalModuleVar(local).st2Acc()
                }
                0x7e.toByte() -> LoadExternalModule(item.asm.code.method.clazz!!.moduleInfo!!.regularImports[item.opUnits[1].toUnsignedInt()]).st2Acc()

                0x7f.toByte() -> AssignObj(ObjField.Name(FunSimCtx.RegId.GLOBAL, (item.ins.format[2] as InstFmt.SId).getString(item)), LoadReg.acc) // stglobalvar

                0x80.toByte() -> ArrayLiteral(emptyList()).st2Acc()
                0x81.toByte() -> ArrayLiteral(
                    JSValue.asArr(item.asm,(item.ins.format[2] as InstFmt.LId).getLA(item)).content.map { ArrayElement.Expr(it.just()) }
                ).st2Acc()
                0x82.toByte() -> JSValue.asObj(item.asm,(item.ins.format[2] as InstFmt.LId).getLA(item)).just().st2Acc()
                0x83.toByte() -> {
                    val clazz = item.opUnits[3].toUnsignedInt()
                    val argC = item.opUnits[2].toUnsignedInt()
                    val args = if(argC.toUnsignedInt() > 1) ((clazz + 1) until (clazz + argC)) else emptyList()
                    AssignReg(FunSimCtx.RegId.ACC, NewInst(regId(clazz), args.map { regId(it) }))
                }
                0x84.toByte() -> UaExp.TypeOf(LoadReg.acc).st2Acc()
                0x85.toByte() -> AssignReg(LoadReg.ACC,ObjField.Value(regId(item.opUnits[2]), LoadReg.ACC))
                0x86.toByte() -> AssignObj(ObjField.Value(regId(item.opUnits[2]), regId(item.opUnits[3])), LoadReg.acc)

                0x88.toByte() -> AssignReg(LoadReg.ACC, ObjField.Index(LoadReg.ACC,item.opUnits[2].toUnsignedInt()))
                0x89.toByte() -> AssignObj(ObjField.Index(regId(item.opUnits[2]),item.opUnits[3].toUnsignedInt()),LoadReg.acc)

                0x8c.toByte() -> ObjField.Name(FunSimCtx.RegId.GLOBAL, (item.ins.format[2] as InstFmt.SId).getString(item)).st2Acc()

                0x90.toByte() -> ObjField.Name(LoadReg.ACC, (item.ins.format[2] as InstFmt.SId).getString(item)).st2Acc()
                0x91.toByte() -> AssignObj(ObjField.Name(regId(item.opUnits[3]),(item.ins.format[2] as InstFmt.SId).getString(item)), LoadReg.acc)

                0x8f.toByte() -> AssignReg(regId(item.opUnits[1]), regId(item.opUnits[2]).ld())

                0x98.toByte() -> Jump(item.opUnits[1].toInt())

                0x9a.toByte() -> JumpIf(item.opUnits[1].toInt(), BiExp.Eq(LoadReg.acc, JSValue.Number(0).just()))
                0x9b.toByte() -> JumpIf(item.opUnits[1].toInt(), BiExp.NEq(LoadReg.acc, JSValue.Number(0).just()))
                0x9c.toByte() -> JumpIf(item.opUnits[1].toInt(), BiExp.NEq(LoadReg.acc, JSValue.Number(0).just()))
                in (0x9d.toByte() .. 0xaa.toByte()) -> Disabled

                0xad.toByte() -> JSValue.Symbol.SymbolObj.just().st2Acc()

                // async / generator 状态机相关指令
                0xae.toByte() -> AsyncFunctionEnter            // asyncfunctionenter
                0xb0.toByte() -> Debugger
                0xb1.toByte() -> NOP                           // creategeneratorobj
                0xb6.toByte() -> NewLexEnvWithName(item.opUnits[1].toUnsignedInt(), parseLexEnvNames(item)) // newlexenvwithname
                0xb7.toByte() -> NOP                           // createasyncgeneratorobj
                0xb8.toByte() -> NOP                           // asyncgeneratorresolve
                0x97.toByte() -> NOP                           // asyncgeneratorreject

                // supercallarrowrange: arrow 函数的 super 调用
                //   opUnits[1] = this reg, opUnits[2] = arg count, opUnits[3] = first arg reg
                0xbb.toByte() -> {
                    val thisReg = item.opUnits[1].toUnsignedInt()
                    val argCount = item.opUnits[2].toUnsignedInt()
                    val firstArg = item.opUnits[3].toUnsignedInt()
                    CallAcc((0 until argCount).map { regId(firstArg + it) }, regId(thisReg)).st2Acc()
                }

                // definemethod (16-bit imm1): ACC 透传
                0xbe.toByte() -> NOP

                0xbd.toByte() -> DynamicImport().st2Acc()

                0xc1.toByte() -> UaExp.GetTemplateObject(LoadReg.acc).st2Acc()
                0xc2.toByte() -> DeleteProp(regId(item.opUnits[1]), FunSimCtx.RegId.ACC)

                // async / generator 状态机相关指令
                0xbf.toByte() -> NOP                                              // resumegenerator
                0xc0.toByte() -> JSValue.Number(0).just().st2Acc()                // getresumemode -> 0，让正常 resume 分支生效
                0xc3.toByte() -> NOP                                              // suspendgenerator
                0xc4.toByte() -> Await(LoadReg.acc)                            // asyncfunctionawaituncaught
                0xcd.toByte() -> Return.ReturnAcc                                 // asyncfunctionresolve
                0xce.toByte() -> Throw.Acc                                        // asyncfunctionreject
                0xd6.toByte() -> NOP                                              // setgeneratorstate
                0xd7.toByte() -> UaExp.GetAsyncIterator(LoadReg.acc).st2Acc()     // getasynciterator

                /**
                 * starrayspread v0, v1
                 * v0: 目标数组寄存器
                 * v1: 插入位置索引寄存器（TS 数组字面量中可忽略，按顺序展开）
                 * ACC: 待展开的可迭代对象
                 */
                0xc6.toByte() -> SpreadIntoArray(regId(item.opUnits[1]), LoadReg.acc)

                0xc7.toByte() -> AssignObj(ObjField.Name(LoadReg.ACC, JSValue.PROTO), regId(item.opUnits[2]).ld())
                0xc8.toByte() -> AssignObj(ObjField.Value(regId(item.opUnits[2]), regId(item.opUnits[3])), LoadReg.acc)

                0xcb.toByte() -> AssignObj(ObjField.Index(regId(item.opUnits[2]),item.opUnits[3].toUnsignedInt()), LoadReg.acc)

                0xcf.toByte() -> CopyRestArgs(item.opUnits[1].toUnsignedInt()).st2Acc()

                0xd3.toByte() -> JSValue.BigInt((item.ins.format[1] as InstFmt.SId).getString(item)).just().st2Acc()

                0xd5.toByte() -> NOP

                0xdb.toByte() -> AssignObj(
                    ObjField.Name(regId(item.opUnits[3]),(item.ins.format[2] as InstFmt.SId).getString(item)),
                    LoadReg.acc
                )
                0xdc.toByte() -> AssignObj(
                    ObjField.Name(regId(item.opUnits[3]),(item.ins.format[2] as InstFmt.SId).getString(item)),
                    LoadReg.acc
                )

                0xbc.toByte() -> DefineGetterSetter(
                    regId(item.opUnits[1]),
                    regId(item.opUnits[2]),
                    regId(item.opUnits[3]),
                    regId(item.opUnits[4])
                )

                else -> UnImplemented(item)
            }
        }

        /**
         * 解析 newlexenvwithname 引用的 literal array，得到每个槽位的变量名。
         * literal array 格式：I32:N, (Str:name, I32:slot) * N
         */
        private fun parseLexEnvNames(item: AsmItem): List<String?> {
            return try {
                val la = (item.ins.format[2] as InstFmt.LId).getLA(item)
                val content = la.content
                if (content.isEmpty()) return emptyList()
                val sizeLit = content[0] as? LiteralArray.Literal.I32 ?: return emptyList()
                val size = sizeLit.value
                if (size <= 0) return emptyList()
                val names = MutableList<String?>(size) { null }
                var i = 1
                while (i + 1 < content.size) {
                    val nameLit = content[i] as? LiteralArray.Literal.Str ?: break
                    val slotLit = content[i + 1] as? LiteralArray.Literal.I32 ?: break
                    val slot = slotLit.value
                    if (slot in names.indices) {
                        names[slot] = nameLit.get(item.asm.code.abc)
                    }
                    i += 2
                }
                names
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    class UnImplemented(val item: AsmItem): IrOp

    sealed interface TraitNOP: IrOp
    class JustAnno(val anno: String): TraitNOP

    object NOP: TraitNOP
    object Debugger: TraitNOP
    object AsyncFunctionEnter: TraitNOP // 标记当前函数为 async 函数
    object Disabled: IrOp //指令功能未使能，暂不可用。
    object Deprecated: IrOp
    /**
     * 创建无名称词法环境（newlexenv / wide.newlexenv）
     */
    class NewLexEnv(val size: Int) : TraitNOP

    /**
     * 创建带名称词法环境（newlexenvwithname / wide.newlexenvwithname）
     * [names] 按 slot 索引存放变量名，未知槽位为 null
     */
    class NewLexEnvWithName(val size: Int, val names: List<String?>) : TraitNOP

    /**
     * 弹出当前词法环境（poplexenv / deprecated.poplexenv）
     */
    object PopLexEnv : TraitNOP


    sealed interface Statement: IrOp, FunSimCtx.Effect

    sealed interface Assign:Statement{

        //若左值是寄存器位置，则返回对应寄存器位置，否则返回null
        val leftReg:FunSimCtx.RegId?
        val right: Expression
        fun replaceRight(newValue: Expression): Assign
    }
    class AssignReg(val left: FunSimCtx.RegId, override val right: Expression): Assign {
        override fun read(): Sequence<FunSimCtx.RegId> = right.read()
        override fun effected(): Sequence<FunSimCtx.RegId> = right.effected() + left

        override val leftReg: FunSimCtx.RegId get() = left
        override fun replaceRight(newValue: Expression): Assign {
            return AssignReg(left, newValue)
        }

    }
    class AssignObj(val left: ObjField, override val right: Expression): Assign {
        override fun read(): Sequence<FunSimCtx.RegId> = right.read() + left.read()
        override fun effected(): Sequence<FunSimCtx.RegId> = right.effected() + left.obj.read()

        override val leftReg: FunSimCtx.RegId? get() = null
        override fun replaceRight(newValue: Expression): Assign {
            return AssignObj(left, newValue)
        }
    }
    class DeleteProp(val obj: FunSimCtx.RegId, val prop: FunSimCtx.RegId): Statement{
        override fun effected(): Sequence<FunSimCtx.RegId> = sequenceOf(obj)
        override fun read(): Sequence<FunSimCtx.RegId> = sequenceOf(obj,prop)
    }
    class DefineGetterSetter(
        val obj: FunSimCtx.RegId,
        val prop: FunSimCtx.RegId,
        val getter: FunSimCtx.RegId,
        val setter: FunSimCtx.RegId
    ): Statement {
        override fun effected(): Sequence<FunSimCtx.RegId> = sequenceOf(obj)
        override fun read(): Sequence<FunSimCtx.RegId> = sequenceOf(obj, prop, getter, setter)
    }

    /**
     * 关闭迭代器（closeiterator）
     */
    class CloseIterator(val iteratorReg: FunSimCtx.RegId) : Statement {
        override fun read(): Sequence<FunSimCtx.RegId> = sequenceOf(iteratorReg)
        override fun effected(): Sequence<FunSimCtx.RegId> = emptySequence()
    }

    class Jump(val offset: Int): Statement
    class JumpIf(val offset: Int,val condition: Expression): Statement{
        override fun read(): Sequence<FunSimCtx.RegId> = condition.read()
        override fun effected(): Sequence<FunSimCtx.RegId> = condition.effected()
    }
    class Return private constructor(val hasValue: Boolean): Statement{
        override fun read(): Sequence<FunSimCtx.RegId> = 
            if (hasValue) sequenceOf(FunSimCtx.RegId.ACC) else emptySequence()
        override fun effected(): Sequence<FunSimCtx.RegId> = emptySequence()
        companion object{
            val ReturnUndefined = Return(false)
            val ReturnAcc = Return(true)
        }
    }
    sealed interface Throw: Statement {
        object Acc: Throw
        class Error(val type: String,val msg: String = ""): Throw
    }

    sealed interface Expression: FunSimCtx.Effect
    sealed interface NoRegExpression: Expression
    class JustImm(val value: JSValue): NoRegExpression
    class LoadReg(val regId: FunSimCtx.RegId): Expression {
        override fun read(): Sequence<FunSimCtx.RegId> = sequenceOf(regId)
        companion object{
            val ACC = FunSimCtx.RegId.ACC
            val acc = FunSimCtx.RegId.ACC.ld()
        }
    }
    class DynamicImport(val regId: FunSimCtx.RegId = LoadReg.ACC): Expression {
        override fun read(): Sequence<FunSimCtx.RegId> = sequenceOf(regId)
    }
    class NewClass(
        val constructor: JSValue.Function,
        val fields: LiteralArray,
        val parent: FunSimCtx.RegId
    ): Expression {
        override fun read(): Sequence<FunSimCtx.RegId> = sequenceOf(parent)
    }
    class NewInst(val clazz: FunSimCtx.RegId, val constructorArgs: List<FunSimCtx.RegId>): Expression {
        override fun read(): Sequence<FunSimCtx.RegId> {
            return constructorArgs.asSequence() + clazz
        }
    }
    class CallAcc(val args: List<FunSimCtx.RegId> = emptyList(), val overrideThis: FunSimCtx.RegId? = null):
        Expression {
        override fun read(): Sequence<FunSimCtx.RegId> = args.asSequence() + FunSimCtx.RegId.ACC
        override fun effected(): Sequence<FunSimCtx.RegId> = sequenceOf(FunSimCtx.RegId.ACC)
    }
    class CallWithTarget(
        val target: Expression,
        val args: List<FunSimCtx.RegId> = emptyList(),
        val overrideThis: FunSimCtx.RegId? = null
    ): Expression {
        override fun read(): Sequence<FunSimCtx.RegId> =
            target.read() + args.asSequence() + (overrideThis?.let { sequenceOf(it) } ?: emptySequence())
        override fun effected(): Sequence<FunSimCtx.RegId> = sequenceOf(FunSimCtx.RegId.ACC)
    }
    class LoadExternalModule(val ext: ModuleLiteralArray.RegularImport): NoRegExpression
    class GetModuleNamespace(val ns: OhmUrl) : NoRegExpression

    /**
     * 加载本地模块变量（ldlocalmodulevar）
     * 从当前模块的 localExports 槽位读取值
     */
    class LoadLocalModuleVar(val local: ModuleLiteralArray.LocalExport) : NoRegExpression

    /**
     * 存储模块变量（stmodulevar）
     * 将值写入当前模块的 localExports 槽位
     */
    class AssignModuleVar(
        val local: ModuleLiteralArray.LocalExport,
        override val right: Expression
    ): Assign {
        override fun read(): Sequence<FunSimCtx.RegId> = right.read()
        override fun effected(): Sequence<FunSimCtx.RegId> = right.effected()
        override val leftReg: FunSimCtx.RegId? get() = null
        override fun replaceRight(newValue: Expression): Assign = AssignModuleVar(local, newValue)
    }

    /**
     * 收集剩余参数（rest parameters）
     * @param startIdx rest 参数在形参列表中的起始位置（0-based，从第一个实际参数开始计数）
     */
    class CopyRestArgs(val startIdx: Int) : NoRegExpression

    /**
     * 数组字面量元素：普通表达式或展开表达式
     */
    sealed interface ArrayElement {
        class Expr(val expr: Expression) : ArrayElement
        class Spread(val expr: Expression) : ArrayElement
    }

    /**
     * 数组字面量表达式 [elem1, elem2, ...spread]
     */
    class ArrayLiteral(val elements: List<ArrayElement>) : NoRegExpression {
        override fun read(): Sequence<FunSimCtx.RegId> = elements.asSequence().flatMap {
            when (it) {
                is ArrayElement.Expr -> it.expr.read()
                is ArrayElement.Spread -> it.expr.read()
            }
        }
    }

    /**
     * 将可迭代对象展开到指定数组寄存器（starrayspread 的临时标记节点）
     */
    class SpreadIntoArray(val arrReg: FunSimCtx.RegId, val source: Expression) : Statement {
        override fun read(): Sequence<FunSimCtx.RegId> = sequenceOf(arrReg) + source.read()
        override fun effected(): Sequence<FunSimCtx.RegId> = sequenceOf(arrReg)
    }

    /**
     * 一元表达式
     */
    sealed class UaExp(
        val source: Expression
    ): Expression {
        override fun read(): Sequence<FunSimCtx.RegId> = source.read()
        override fun effected(): Sequence<FunSimCtx.RegId> = source.effected()

        class TypeOf(source: Expression) : UaExp(source)
        class ToNumber(source: Expression) : UaExp(source)
        class ToNumeric(source: Expression) : UaExp(source)
        class Neg(source: Expression) : UaExp(source)
        class Not(source: Expression) : UaExp(source)
        class Inc(source: Expression) : UaExp(source)
        class Dec(source: Expression) : UaExp(source)
        class IsTrue(source: Expression) : UaExp(source)
        class IsFalse(source: Expression) : UaExp(source)

        class GetTemplateObject(source: Expression) : UaExp(source)
        class GetAsyncIterator(source: Expression) : UaExp(source)
        class GetIterator(source: Expression) : UaExp(source)
        class GetPropIterator(source: Expression) : UaExp(source)
        class GetNextPropName(val iteratorReg: FunSimCtx.RegId) : UaExp(LoadReg(iteratorReg))
        class DeprecatedGetIteratorNext(
            val iteratorReg: FunSimCtx.RegId,
            val nextReg: FunSimCtx.RegId
        ) : UaExp(LoadReg(iteratorReg)) {
            override fun read(): Sequence<FunSimCtx.RegId> = sequenceOf(iteratorReg, nextReg)
        }

    }

    /**
     * await 表达式语句。
     * 单独作为 Statement 而不是 AssignReg，避免被死代码消除误删（await 有副作用）。
     */
    class Await(val source: Expression) : Statement {
        override fun read(): Sequence<FunSimCtx.RegId> = source.read()
        override fun effected(): Sequence<FunSimCtx.RegId> = sequenceOf(FunSimCtx.RegId.ACC)
    }

    /**
     * 二元表达式
     */
    sealed class BiExp(
        val l: Expression,
        val r: Expression,
    ): Expression {
        override fun read(): Sequence<FunSimCtx.RegId> = l.read() + r.read()
        override fun effected(): Sequence<FunSimCtx.RegId> = l.effected() + r.effected()
        class Add(l: Expression, r: Expression) : BiExp(l, r)
        class Sub(l: Expression, r: Expression) : BiExp(l, r)
        class Mul(l: Expression, r: Expression) : BiExp(l, r)
        class Div(l: Expression, r: Expression) : BiExp(l, r)
        class Mod(l: Expression, r: Expression) : BiExp(l, r)
        class Eq(l: Expression, r: Expression) : BiExp(l, r)
        class NEq(l: Expression, r: Expression) : BiExp(l, r)
        class Less(l: Expression, r: Expression) : BiExp(l, r)
        class LEq(l: Expression, r: Expression) : BiExp(l, r)
        class Ge(l: Expression, r: Expression) : BiExp(l, r)
        class GEq(l: Expression, r: Expression) : BiExp(l, r)
        class Shl(l: Expression, r: Expression) : BiExp(l, r)
        class Shr(l: Expression, r: Expression) : BiExp(l, r)
        class AShr(l: Expression, r: Expression) : BiExp(l, r)
        class And(l: Expression, r: Expression) : BiExp(l, r)
        class Or(l: Expression, r: Expression) : BiExp(l, r)
        class Xor(l: Expression, r: Expression) : BiExp(l, r)
        class Exp(l: Expression, r: Expression) : BiExp(l, r)

        class IsIn(l: Expression, r: Expression) : BiExp(l, r)
        class InstOf(l: Expression, r: Expression) : BiExp(l, r)
        class StrictNEq(l: Expression, r: Expression) : BiExp(l, r)
        class StrictEq(l: Expression, r: Expression) : BiExp(l, r)
    }

    sealed class ObjField(val obj: Expression): Expression {
        override fun read(): Sequence<FunSimCtx.RegId> {
            return obj.read()
        }
        // 向后兼容：接受 RegId 的构造函数
        constructor(obj: FunSimCtx.RegId) : this(LoadReg(obj))
        
        class Name(obj: Expression, val name: String): ObjField(obj) {
            constructor(obj: FunSimCtx.RegId, name: String) : this(LoadReg(obj), name)
        }
        class Index(obj: Expression, val index: Int): ObjField(obj) {
            constructor(obj: FunSimCtx.RegId, index: Int) : this(LoadReg(obj), index)
        }
        class Value(obj: Expression, val value: FunSimCtx.RegId): ObjField(obj) {
            constructor(obj: FunSimCtx.RegId, value: FunSimCtx.RegId) : this(LoadReg(obj), value)
            override fun read(): Sequence<FunSimCtx.RegId> {
                return super.read() + sequenceOf(value)
            }
        }
    }

}