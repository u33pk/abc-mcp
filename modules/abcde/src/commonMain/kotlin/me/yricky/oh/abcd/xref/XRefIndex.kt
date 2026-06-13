package me.yricky.oh.abcd.xref

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.cfm.AbcMethod
import me.yricky.oh.abcd.decompiler.behaviour.FunSimCtx
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.decompiler.behaviour.JSValue
import me.yricky.oh.abcd.decompiler.structure.decodeMethodName
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.abcd.isa.calledMethods

/**
 * 交叉引用索引
 *
 * 借鉴 jadx 的 UsageInfo 设计：扫描一次全库，建立“目标 -> 引用源”的反向索引。
 * 当前支持方法调用、字段读写（类内同名字段启发式索引 + 名字兜底索引）。
 *
 * 字段 xref 说明：ABC 字节码中 `this.fieldName` 往往先被加载到 ACC 或局部寄存器，再执行字段访问，
 * 无法像方法调用那样直接识别对象类型；且 ArkTS 编译后的类字段表通常只含模块元数据，不含实例字段。
 * 因此采用启发式策略：若在某类 C 的方法内访问字段名 f，则视为 C.f 的可能引用；
 * 同时保留按字段名的兜底索引，用于跨类同名字段查询。
 */
class XRefIndex private constructor(
    /** 被调方法 -> 调用它的位置列表 */
    val methodCallers: Map<AbcMethod, List<XRefLocation>>,
    /** (类名, 字段名) -> 读取它的位置列表（类内启发式） */
    val fieldReaders: Map<Pair<String, String>, List<XRefLocation>>,
    /** (类名, 字段名) -> 写入它的位置列表（类内启发式） */
    val fieldWriters: Map<Pair<String, String>, List<XRefLocation>>,
    /** 字段名 -> 读取它的位置列表（对象类型未知时的兜底索引） */
    val nameBasedFieldReaders: Map<String, List<XRefLocation>>,
    /** 字段名 -> 写入它的位置列表（对象类型未知时的兜底索引） */
    val nameBasedFieldWriters: Map<String, List<XRefLocation>>,
    /** 类名 -> 实例化它的位置列表 */
    val classInstantiations: Map<String, List<XRefLocation>>,
) {
    /**
     * 查询方法的调用者（谁调用了这个方法）
     */
    fun getCallers(method: AbcMethod): List<XRefLocation> {
        return methodCallers[method] ?: emptyList()
    }

    /**
     * 查询字段在指定类内的读取者（启发式）
     */
    fun getFieldReaders(className: String, fieldName: String): List<XRefLocation> {
        return fieldReaders[className to fieldName] ?: emptyList()
    }

    /**
     * 查询字段在指定类内的写入者（启发式）
     */
    fun getFieldWriters(className: String, fieldName: String): List<XRefLocation> {
        return fieldWriters[className to fieldName] ?: emptyList()
    }

    /**
     * 按字段名查询可能的读取位置（用于对象类型无法推断时）
     */
    fun getNameBasedFieldReaders(fieldName: String): List<XRefLocation> {
        return nameBasedFieldReaders[fieldName] ?: emptyList()
    }

    /**
     * 按字段名查询可能的写入位置（用于对象类型无法推断时）
     */
    fun getNameBasedFieldWriters(fieldName: String): List<XRefLocation> {
        return nameBasedFieldWriters[fieldName] ?: emptyList()
    }

    /**
     * 查询类的实例化位置（谁在 new 这个类）
     */
    fun getInstantiations(className: String): List<XRefLocation> {
        return classInstantiations[className] ?: emptyList()
    }

    companion object {
        /**
         * 从 [AbcBuf] 构建交叉引用索引
         */
        fun build(abc: AbcBuf): XRefIndex {
            val callerMap = mutableMapOf<AbcMethod, MutableList<XRefLocation>>()
            val fieldReaders = mutableMapOf<Pair<String, String>, MutableList<XRefLocation>>()
            val fieldWriters = mutableMapOf<Pair<String, String>, MutableList<XRefLocation>>()
            val nameBasedReaders = mutableMapOf<String, MutableList<XRefLocation>>()
            val nameBasedWriters = mutableMapOf<String, MutableList<XRefLocation>>()
            val classInstantiations = mutableMapOf<String, MutableList<XRefLocation>>()

            for (classItem in abc.classes.values) {
                if (classItem !is AbcClass) continue
                for (method in classItem.methods) {
                    val code = method.codeItem ?: continue
                    val asm = Asm(code)
                    val regExpressionMap = buildRegExpressionMap(asm)
                    for (item in asm.list) {
                        // 方法调用 xref
                        for (callee in item.calledMethods) {
                            val loc = XRefLocation(
                                callerClass = classItem.name,
                                callerMethod = method.name,
                                callerDecodedName = decodeMethodName(method),
                                codeOffset = item.codeOffset
                            )
                            callerMap.getOrPut(callee) { mutableListOf() }.add(loc)
                        }

                        // 字段读写 xref
                        val (reads, writes) = collectFieldAccesses(item.irOp)
                        for (fieldAccess in reads) {
                            val loc = XRefLocation(
                                callerClass = classItem.name,
                                callerMethod = method.name,
                                callerDecodedName = decodeMethodName(method),
                                codeOffset = item.codeOffset
                            )
                            // 启发式：在类 C 的方法内访问字段名 f，视为 C.f 的可能引用
                            fieldReaders.getOrPut(classItem.name to fieldAccess.name) { mutableListOf() }.add(loc)
                            // 同时进入名字兜底索引，用于跨类同名字段查询
                            nameBasedReaders.getOrPut(fieldAccess.name) { mutableListOf() }.add(loc)
                        }
                        for (fieldAccess in writes) {
                            val loc = XRefLocation(
                                callerClass = classItem.name,
                                callerMethod = method.name,
                                callerDecodedName = decodeMethodName(method),
                                codeOffset = item.codeOffset
                            )
                            fieldWriters.getOrPut(classItem.name to fieldAccess.name) { mutableListOf() }.add(loc)
                            nameBasedWriters.getOrPut(fieldAccess.name) { mutableListOf() }.add(loc)
                        }

                        // 类实例化 xref
                        val instantiatedClasses = collectInstantiations(item.irOp, regExpressionMap)
                        for (instantiatedClass in instantiatedClasses) {
                            val loc = XRefLocation(
                                callerClass = classItem.name,
                                callerMethod = method.name,
                                callerDecodedName = decodeMethodName(method),
                                codeOffset = item.codeOffset
                            )
                            classInstantiations.getOrPut(instantiatedClass) { mutableListOf() }.add(loc)
                        }
                    }
                }
            }

            return XRefIndex(
                methodCallers = callerMap.mapValues { it.value.toList() },
                fieldReaders = fieldReaders.mapValues { it.value.toList() },
                fieldWriters = fieldWriters.mapValues { it.value.toList() },
                nameBasedFieldReaders = nameBasedReaders.mapValues { it.value.toList() },
                nameBasedFieldWriters = nameBasedWriters.mapValues { it.value.toList() },
                classInstantiations = classInstantiations.mapValues { it.value.toList() },
            )
        }

        /**
         * 从一条 IR 指令中收集字段访问。
         * @return Pair<读取列表, 写入列表>
         */
        private fun collectFieldAccesses(irOp: IrOp): Pair<List<IrOp.ObjField.Name>, List<IrOp.ObjField.Name>> {
            val reads = mutableListOf<IrOp.ObjField.Name>()
            val writes = mutableListOf<IrOp.ObjField.Name>()

            fun visitExpression(expr: IrOp.Expression, isWrite: Boolean) {
                when (expr) {
                    is IrOp.ObjField.Name -> {
                        if (isWrite) writes.add(expr) else reads.add(expr)
                    }
                    is IrOp.ObjField.Index, is IrOp.ObjField.Value -> {
                        // 动态字段访问，只递归对象，不记录
                        (expr as IrOp.ObjField).obj.let { visitExpression(it, false) }
                    }
                    is IrOp.BiExp -> {
                        visitExpression(expr.l, false)
                        visitExpression(expr.r, false)
                    }
                    is IrOp.UaExp -> visitExpression(expr.source, false)
                    is IrOp.CallWithTarget -> visitExpression(expr.target, false)
                    is IrOp.ObjField -> visitExpression(expr.obj, false)
                    else -> { /* LoadReg, JustImm, NewClass, NewInst, CallAcc, DynamicImport 等无嵌套字段访问 */ }
                }
            }

            fun visitStatement(stmt: IrOp) {
                when (stmt) {
                    is IrOp.AssignObj -> {
                        visitExpression(stmt.left, true)
                        visitExpression(stmt.right, false)
                    }
                    is IrOp.AssignReg -> visitExpression(stmt.right, false)
                    is IrOp.JumpIf -> visitExpression(stmt.condition, false)
                    is IrOp.DeleteProp -> { /* 动态删除，不记录 */ }
                    is IrOp.DefineGetterSetter -> { /* 动态 getter/setter，不记录 */ }
                    else -> { /* NOP, Jump, Return, Throw, UnImplemented 等 */ }
                }
            }

            visitStatement(irOp)
            return reads to writes
        }

        /**
         * 线性扫描方法，建立寄存器到最近表达式的映射（用于 NewInst 类名回溯）。
         */
        private fun buildRegExpressionMap(asm: Asm): Map<FunSimCtx.RegId, IrOp.Expression> {
            val map = mutableMapOf<FunSimCtx.RegId, IrOp.Expression>()
            for (item in asm.list) {
                val op = item.irOp
                if (op is IrOp.AssignReg) {
                    map[op.left] = op.right
                }
            }
            return map
        }

        /**
         * 从寄存器出发，尝试解析出类名。
         */
        private fun resolveClassName(
            regId: FunSimCtx.RegId,
            regMap: Map<FunSimCtx.RegId, IrOp.Expression>,
            visited: MutableSet<FunSimCtx.RegId> = mutableSetOf()
        ): String? {
            if (!visited.add(regId)) return null
            return when (val expr = regMap[regId]) {
                is IrOp.JustImm -> (expr.value as? JSValue.Function)?.method?.clazz?.name
                is IrOp.LoadReg -> resolveClassName(expr.regId, regMap, visited)
                is IrOp.LoadExternalModule -> expr.ext.localName
                else -> null
            }
        }

        /**
         * 从一条 IR 指令中收集实例化的类名。
         */
        private fun collectInstantiations(
            irOp: IrOp,
            regMap: Map<FunSimCtx.RegId, IrOp.Expression>
        ): List<String> {
            val result = mutableListOf<String>()

            fun visitExpr(expr: IrOp.Expression) {
                when (expr) {
                    is IrOp.NewClass -> {
                        expr.constructor.method.clazz?.name?.let { result.add(it) }
                    }
                    is IrOp.NewInst -> {
                        resolveClassName(expr.clazz, regMap)?.let { result.add(it) }
                    }
                    is IrOp.BiExp -> {
                        visitExpr(expr.l)
                        visitExpr(expr.r)
                    }
                    is IrOp.UaExp -> visitExpr(expr.source)
                    is IrOp.CallWithTarget -> visitExpr(expr.target)
                    is IrOp.ObjField.Name -> visitExpr(expr.obj)
                    is IrOp.ObjField.Index -> visitExpr(expr.obj)
                    is IrOp.ObjField.Value -> {
                        visitExpr(expr.obj)
                        visitExpr(IrOp.LoadReg(expr.value))
                    }
                    else -> { /* LoadReg, JustImm, DynamicImport, LoadExternalModule 等不会直接产生实例化 */ }
                }
            }

            fun visitStmt(stmt: IrOp) {
                when (stmt) {
                    is IrOp.AssignReg -> visitExpr(stmt.right)
                    is IrOp.AssignObj -> {
                        visitExpr(stmt.left)
                        visitExpr(stmt.right)
                    }
                    is IrOp.JumpIf -> visitExpr(stmt.condition)
                    is IrOp.DeleteProp -> { /* 动态删除，不记录 */ }
                    is IrOp.DefineGetterSetter -> { /* 动态 getter/setter，不记录 */ }
                    else -> { /* NOP, Jump, Return, Throw, UnImplemented 等 */ }
                }
            }

            visitStmt(irOp)
            return result
        }
    }
}

/**
 * 一个交叉引用位置
 */
data class XRefLocation(
    /** 调用方类全名 */
    val callerClass: String,
    /** 调用方方法原始名 */
    val callerMethod: String,
    /** 调用方方法解码后的可读名 */
    val callerDecodedName: String,
    /** 调用指令在调用方方法内的 offset */
    val codeOffset: Int
) {
    /**
     * 调用方类名.方法名（解码后）
     */
    val callerFullName: String get() = "$callerClass.$callerDecodedName"
}
