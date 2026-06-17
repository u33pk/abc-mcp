package me.yricky.oh.abcd.decompiler.structure.reconstruction

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.cfm.AbcMethod
import me.yricky.oh.abcd.decompiler.CodeSegment
import me.yricky.oh.abcd.decompiler.behaviour.FunSimCtx
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.decompiler.behaviour.JSValue
import me.yricky.oh.abcd.decompiler.structure.*
import me.yricky.oh.abcd.decompiler.structure.statement.*
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.abcd.xref.ClassHierarchyIndex

/**
 * Class 重组 Pass。
 *
 * 在 Region 树中扫描 `func_main_0` 的 `defineclasswithbuffer`（对应 `IrOp.NewClass`）
 * 及其后续成员定义，将其替换为 `ClassDeclaration` 节点。
 *
 * 当前支持模式：
 * 1. `NewClass` 创建类对象
 * 2. `definemethod` + `definegettersetterbyvalue` 定义字段 getter/setter
 * 3. `definemethod` + `stownbyname` 定义普通实例方法
 * 4. `definemethod` + 赋值到 class 对象定义静态方法
 */
object ClassReconstructionPass {

    /**
     * 对 [region] 执行 class 重组，返回识别出的所有 [ClassDeclaration]。
     */
    fun reconstruct(region: Region): List<ClassDeclaration> {
        val declarations = mutableListOf<ClassDeclaration>()
        val seenConstructors = mutableSetOf<AbcMethod>()
        processRegion(region, declarations, seenConstructors)
        return declarations
    }

    private fun processRegion(
        region: Region,
        declarations: MutableList<ClassDeclaration>,
        seenConstructors: MutableSet<AbcMethod>
    ) {
        val newStatements = mutableListOf<Decompilable>()
        for (stmt in region.statements) {
            when (stmt) {
                is LinearStatement -> {
                    val block = stmt.block
                    if (block is CodeSegment.Linear) {
                        val items = block.toList()
                        val result = extractClasses(items, block.next, seenConstructors)
                        declarations.addAll(result.first)
                        newStatements.addAll(result.first)
                        val remainingItems = result.second
                        if (remainingItems.isNotEmpty()) {
                            newStatements.add(LinearStatement(createLinear(remainingItems, block.next)))
                        }
                    } else {
                        newStatements.add(stmt)
                    }
                }
                is IfStatement -> {
                    stmt.thenBranch?.let { processRegion(it, declarations, seenConstructors) }
                    stmt.elseBranch?.let { processRegion(it, declarations, seenConstructors) }
                    newStatements.add(stmt)
                }
                is WhileStatement -> {
                    processRegion(stmt.body, declarations, seenConstructors)
                    newStatements.add(stmt)
                }
                is DoWhileStatement -> {
                    processRegion(stmt.body, declarations, seenConstructors)
                    newStatements.add(stmt)
                }
                is ForOfStatement -> {
                    processRegion(stmt.body, declarations, seenConstructors)
                    newStatements.add(stmt)
                }
                is ForInStatement -> {
                    processRegion(stmt.body, declarations, seenConstructors)
                    newStatements.add(stmt)
                }
                else -> newStatements.add(stmt)
            }
        }
        region.statements.clear()
        region.statements.addAll(newStatements)
    }

    /**
     * 从线性指令列表中提取 class 定义。
     *
     * @return Pair<识别出的 ClassDeclaration 列表, 剩余未识别的指令>
     */
    private fun extractClasses(
        items: List<Asm.AsmItem>,
        blockNext: Int,
        seenConstructors: MutableSet<AbcMethod>
    ): Pair<List<ClassDeclaration>, List<Asm.AsmItem>> {
        val declarations = mutableListOf<ClassDeclaration>()
        val remaining = mutableListOf<Asm.AsmItem>()
        var i = 0
        while (i < items.size) {
            val newClassInfo = findNewClass(items, i) ?: run {
                remaining.add(items[i])
                i++
                continue
            }

            val ctorMethod = newClassInfo.newClassOp.constructor.method as? AbcMethod
            val isDuplicate = ctorMethod != null && ctorMethod in seenConstructors
            if (isDuplicate) {
                // 已识别过相同构造器的 class，跳过整个 class 创建块，保留前后指令
                if (newClassInfo.startIndex > i) {
                    remaining.addAll(items.subList(i, newClassInfo.startIndex))
                }
                val (_, endIndex) = collectClassMembers(items, newClassInfo, extractOnly = true)
                i = endIndex
                continue
            }
            ctorMethod?.let { seenConstructors.add(it) }

            // 保留 class 块之前的指令
            if (newClassInfo.startIndex > i) {
                remaining.addAll(items.subList(i, newClassInfo.startIndex))
            }

            val (clazz, endIndex) = collectClassMembers(items, newClassInfo, extractOnly = false)
            declarations.add(ClassDeclaration(clazz!!))
            i = endIndex
        }
        return declarations to remaining
    }

    private data class NewClassInfo(
        val startIndex: Int,
        val newClassItem: Asm.AsmItem,
        val newClassOp: IrOp.NewClass,
        val classReg: FunSimCtx.RegId,
        val className: String,
        val superClassName: String?
    )

    private fun findNewClass(items: List<Asm.AsmItem>, start: Int): NewClassInfo? {
        for (i in start until items.size) {
            val op = items[i].irOp
            val newClassExpr = when (op) {
                is IrOp.AssignReg -> op.right as? IrOp.NewClass
                else -> null
            } ?: continue

            val classReg = (op as IrOp.AssignReg).left
            val className = decodeClassName(newClassExpr)
            val superClassName = resolveSuperClass(items[i], newClassExpr.parent)
            return NewClassInfo(i, items[i], newClassExpr, classReg, className, superClassName)
        }
        return null
    }

    /**
     * 从 constructor method 名中解码类名。
     *
     * 例如 `this.#~@0=#SimpleBrowser` -> `SimpleBrowser`
     */
    private fun decodeClassName(newClass: IrOp.NewClass): String {
        val raw = newClass.constructor.method.name
        // 取最后一个 `=` 或 `#` 后的名称
        return raw.substringAfterLast("=").substringAfterLast("#").takeIf { it.isNotEmpty() } ?: raw
    }

    /**
     * 解析 NewClass 的 parent 寄存器，尝试回溯到父类名。
     * 使用完整方法指令列表构建寄存器映射，以处理跨基本块的父类赋值。
     * 若能在 ABC 中定位到父类则返回全限定名；否则返回可读的表达式字符串
     *（如 `AtkTsGlobal.ViewPU`），保证反编译输出中仍能看到继承关系。
     */
    private fun resolveSuperClass(
        newClassItem: Asm.AsmItem,
        parentReg: FunSimCtx.RegId
    ): String? {
        val allItems = newClassItem.asm.list
        val regMap = buildRegMap(allItems, newClassItem.index)
        val resolved = ClassHierarchyIndex.resolveClassName(parentReg, regMap)
        if (resolved != null) return resolved

        val abc = newClassItem.asm.code.method.clazz?.abc
        val expr = followLoadReg(regMap[parentReg], regMap)

        // 优先匹配 ABC 中的类
        val globalName = resolveGlobalName(expr, regMap)
        if (globalName != null && abc != null) {
            val inAbc = resolveClassNameInAbc(abc, globalName)
            if (inAbc != null) return inAbc
        }

        // 回退为可读字符串
        return expressionToSuperString(expr)
    }

    /**
     * 沿 LoadReg 链展开表达式，避免循环。
     */
    private fun followLoadReg(
        expr: IrOp.Expression?,
        regMap: Map<FunSimCtx.RegId, IrOp.Expression>,
        visited: MutableSet<FunSimCtx.RegId> = mutableSetOf()
    ): IrOp.Expression? {
        return when (expr) {
            is IrOp.LoadReg -> {
                if (!visited.add(expr.regId)) return expr
                followLoadReg(regMap[expr.regId], regMap, visited)
            }
            else -> expr
        }
    }

    /**
     * 从表达式链中提取全局对象上的属性名（如 `AtkTsGlobal.ViewPU` -> "ViewPU"）。
     */
    private fun resolveGlobalName(
        expr: IrOp.Expression?,
        regMap: Map<FunSimCtx.RegId, IrOp.Expression>,
        visited: MutableSet<FunSimCtx.RegId> = mutableSetOf()
    ): String? {
        return when (expr) {
            is IrOp.ObjField.Name -> {
                if (expr.obj.regId() == FunSimCtx.RegId.GLOBAL) expr.name else null
            }
            is IrOp.LoadReg -> {
                if (!visited.add(expr.regId)) return null
                resolveGlobalName(regMap[expr.regId], regMap, visited)
            }
            else -> null
        }
    }

    /**
     * 在 ABC 中按短名或全限定名查找类。
     */
    private fun resolveClassNameInAbc(abc: AbcBuf, name: String): String? {
        val classes = abc.classes.values.filterIsInstance<AbcClass>()
        val exact = classes.find { it.name == name }
        if (exact != null) return exact.name
        val candidates = classes.filter { it.name.endsWith("/$name") || it.name == name }
        return if (candidates.size == 1) candidates.first().name else null
    }

    /**
     * 将父类表达式转换为可读字符串，用于在无法解析为 ABC 类名时仍展示 extends。
     */
    private fun expressionToSuperString(expr: IrOp.Expression?): String? {
        return when (expr) {
            is IrOp.ObjField.Name -> {
                when (expr.obj.regId()) {
                    FunSimCtx.RegId.GLOBAL -> "AtkTsGlobal.${expr.name}"
                    else -> null
                }
            }
            is IrOp.LoadExternalModule -> expr.ext.localName
            is IrOp.NewClass -> expr.constructor.method.clazz?.name
            else -> null
        }
    }

    /**
     * 收集一个 class 的成员定义。
     *
     * @param extractOnly 为 true 时只计算 class 块边界，不收集字段/方法（用于跳过重复 class）
     * @return Pair<重组后的类信息, class 块结束后的下一个指令索引>
     */
    private fun collectClassMembers(
        items: List<Asm.AsmItem>,
        info: NewClassInfo,
        extractOnly: Boolean
    ): Pair<ReconstructedClass?, Int> {
        val fields = mutableListOf<ClassField>()
        val instanceMethods = mutableListOf<ClassMethod>()
        val staticMethods = mutableListOf<ClassMethod>()

        // 追踪 class 对象寄存器和 prototype 寄存器
        val regAliases = mutableMapOf<FunSimCtx.RegId, RegValue>()
        regAliases[info.classReg] = RegValue.ClassObject(info.classReg)

        // 记录 class 块内每个寄存器的当前表达式，用于解析后续操作数
        val regValues = mutableMapOf<FunSimCtx.RegId, IrOp.Expression>()

        var i = info.startIndex + 1
        while (i < items.size) {
            val item = items[i]
            val op = item.irOp

            // 记录 AssignReg 的右值
            if (op is IrOp.AssignReg) {
                regValues[op.left] = op.right
            }

            // 识别 prototype 提取：vX = classReg.prototype
            if (op is IrOp.AssignReg && op.right is IrOp.ObjField.Name) {
                val field = op.right as IrOp.ObjField.Name
                val objRegId = field.obj.regId()
                if (field.name == "prototype" && objRegId != null) {
                    val alias = regAliases[objRegId]
                    if (alias is RegValue.ClassObject && alias.classReg == info.classReg) {
                        val protoValue = RegValue.Prototype(info.classReg, op.left)
                        regAliases[op.left] = protoValue
                        // ldobjbyname 通常同时把结果写入 ACC，也标记 ACC 为 prototype
                        regAliases[FunSimCtx.RegId.ACC] = protoValue
                        i++
                        continue
                    }
                }
            }

            if (extractOnly) {
                // 仅扫描边界，允许 class 相关指令透传
                if (op is IrOp.AssignReg || op is IrOp.AssignObj || op is IrOp.Statement || op is IrOp.NOP) {
                    i++
                    continue
                }
                // 控制流或新 class 结束重复块
                break
            }

            // definemethod 当前映射为 NOP，直接跳过
            if (op is IrOp.NOP) {
                i++
                continue
            }

            // 识别字段定义：Object.defineProperty(protoReg, "name", {get: getter, set: setter})
            if (op is IrOp.DefineGetterSetter) {
                val objValue = regAliases[op.obj]
                if (objValue is RegValue.Prototype && objValue.classReg == info.classReg) {
                    val fieldName = resolvePropName(items, i, op.prop)
                    if (fieldName != null) {
                        val getter = resolveMethodFromExpression(items, i, IrOp.LoadReg(op.getter))
                        val setter = resolveMethodFromExpression(items, i, IrOp.LoadReg(op.setter))
                        val existing = fields.find { it.name == fieldName }
                        if (existing != null) {
                            fields.remove(existing)
                            fields.add(ClassField(fieldName, getter ?: existing.getter, setter ?: existing.setter))
                        } else {
                            fields.add(ClassField(fieldName, getter, setter))
                        }
                    }
                    i++
                    continue
                }
            }

            // 识别方法挂载：protoReg.methodName = methodReg / classReg.methodName = methodReg
            if (op is IrOp.AssignObj && op.left is IrOp.ObjField.Name) {
                val leftField = op.left as IrOp.ObjField.Name
                val targetValue = regAliases[leftField.obj.regId()]
                val method = resolveMethodFromExpression(items, i, op.right)
                if (method != null && targetValue != null) {
                    val decodedName = me.yricky.oh.abcd.decompiler.structure.decodeMethodName(method)
                    when (targetValue) {
                        is RegValue.ClassObject -> {
                            staticMethods.add(ClassMethod(decodedName, method, isStatic = true))
                        }
                        is RegValue.Prototype -> {
                            instanceMethods.add(ClassMethod(decodedName, method, isStatic = false))
                        }
                    }
                    i++
                    continue
                }
            }

            // 识别 class 对象 / prototype 别名：vX = classReg / vX = protoReg
            if (op is IrOp.AssignReg && op.right is IrOp.LoadReg) {
                val srcValue = regAliases[op.right.regId]
                if (srcValue != null && srcValue.classReg == info.classReg) {
                    regAliases[op.left] = srcValue
                    i++
                    continue
                }
            }

            // 另一个 NewClass，当前 class 块结束
            if (op is IrOp.AssignReg && op.right is IrOp.NewClass) {
                break
            }

            // 控制流指令结束 class 块
            if (op is IrOp.Return || op is IrOp.Jump || op is IrOp.JumpIf || op is IrOp.Throw) {
                break
            }

            // 允许其他临时赋值/加载指令继续扫描（它们可能是 DefineGetterSetter 的操作数准备）
            if (op is IrOp.AssignReg || op is IrOp.AssignObj || op is IrOp.Statement) {
                i++
                continue
            }

            // 其他不认识的指令结束 class 块
            break
        }

        val endOffset = if (i > info.startIndex) {
            items[i - 1].codeOffset + (items[i - 1].nextOffset - items[i - 1].codeOffset)
        } else {
            info.newClassItem.codeOffset
        }

        if (extractOnly) {
            return null to i
        }

        // defineclasswithbuffer 的 class literal buffer 中通常已包含实例方法（如 build、aboutToAppear）。
        // 将 buffer 中的方法补充为 class 成员，避免后续 prototype 挂载扫描遗漏。
        val seenMethodOffsets = mutableSetOf<Int>()
        instanceMethods.forEach { seenMethodOffsets.add(it.method.offset) }
        staticMethods.forEach { seenMethodOffsets.add(it.method.offset) }
        val ctor = info.newClassOp.constructorMethod
        for (bm in info.newClassOp.parsedBufferMethods) {
            if (bm.method == ctor) continue
            if (!seenMethodOffsets.add(bm.method.offset)) continue
            val decodedName = me.yricky.oh.abcd.decompiler.structure.decodeMethodName(bm.method)
            // MethodAffiliate 非 0 时倾向于是静态方法（具体语义随编译器版本可能变化，此处保守处理）
            if (bm.affiliate.toInt() != 0) {
                staticMethods.add(ClassMethod(decodedName, bm.method, isStatic = true))
            } else {
                instanceMethods.add(ClassMethod(decodedName, bm.method, isStatic = false))
            }
        }

        val clazz = ReconstructedClass(
            className = info.className,
            superClassName = info.superClassName,
            constructorMethod = ctor,
            bufferMethods = info.newClassOp.parsedBufferMethods,
            fields = fields,
            instanceMethods = instanceMethods,
            staticMethods = staticMethods,
            newClassReg = info.classReg,
            startOffset = info.newClassItem.codeOffset,
            endOffset = endOffset
        )
        return clazz to i
    }

    /**
     * 寄存器值的抽象，用于追踪 class 对象和 prototype。
     */
    private sealed class RegValue {
        abstract val classReg: FunSimCtx.RegId
        data class ClassObject(override val classReg: FunSimCtx.RegId) : RegValue()
        data class Prototype(override val classReg: FunSimCtx.RegId, val protoReg: FunSimCtx.RegId) : RegValue()
    }

    private fun IrOp.Expression.regId(): FunSimCtx.RegId? = (this as? IrOp.LoadReg)?.regId

    /**
     * 从表达式出发解析方法对象。
     */
    private fun resolveMethodFromExpression(
        items: List<Asm.AsmItem>,
        endIndex: Int,
        expr: IrOp.Expression
    ): AbcMethod? {
        return when (expr) {
            is IrOp.LoadReg -> resolveMethodFromReg(items, endIndex, expr.regId)
            is IrOp.JustImm -> (expr.value as? JSValue.Function)?.method as? AbcMethod
            else -> null
        }
    }

    /**
     * 从寄存器出发，回溯最近赋给该寄存器的方法对象。
     */
    private fun resolveMethodFromReg(
        items: List<Asm.AsmItem>,
        endIndex: Int,
        regId: FunSimCtx.RegId
    ): AbcMethod? {
        for (j in (endIndex - 1) downTo 0) {
            val op = items[j].irOp
            if (op is IrOp.AssignReg && op.left == regId) {
                return resolveMethodFromExpression(items, j, op.right)
            }
        }
        return null
    }

    /**
     * 解析属性名字符串，支持沿 LoadReg 链回溯（包括 ACC 中转）。
     */
    private fun resolvePropName(
        items: List<Asm.AsmItem>,
        endIndex: Int,
        propReg: FunSimCtx.RegId,
        visited: MutableSet<FunSimCtx.RegId> = mutableSetOf()
    ): String? {
        if (!visited.add(propReg)) return null
        for (j in (endIndex - 1) downTo 0) {
            val op = items[j].irOp
            if (op is IrOp.AssignReg && op.left == propReg) {
                return when (val expr = op.right) {
                    is IrOp.JustImm -> if (expr.value is JSValue.Str) expr.value.value else null
                    is IrOp.LoadReg -> resolvePropName(items, j, expr.regId, visited)
                    else -> null
                }
            }
        }
        return null
    }

    /**
     * 从当前 class 块的寄存器缓存中解析属性名，支持沿 LoadReg 链回溯（包括 ACC 中转）。
     */
    private fun resolvePropNameWithCache(
        regValues: Map<FunSimCtx.RegId, IrOp.Expression>,
        propReg: FunSimCtx.RegId,
        visited: MutableSet<FunSimCtx.RegId> = mutableSetOf()
    ): String? {
        if (!visited.add(propReg)) return null
        val expr = regValues[propReg]
        return when (expr) {
            is IrOp.JustImm -> if (expr.value is JSValue.Str) expr.value.value else null
            is IrOp.LoadReg -> resolvePropNameWithCache(regValues, expr.regId, visited)
            else -> null
        }
    }

    /**
     * 从当前 class 块的寄存器缓存中解析方法对象。
     */
    private fun resolveMethodFromExpressionWithCache(
        regValues: Map<FunSimCtx.RegId, IrOp.Expression>,
        expr: IrOp.Expression,
        visited: MutableSet<FunSimCtx.RegId> = mutableSetOf()
    ): AbcMethod? {
        return when (expr) {
            is IrOp.LoadReg -> {
                if (!visited.add(expr.regId)) return null
                val regExpr = regValues[expr.regId]
                resolveMethodFromExpressionWithCache(regValues, regExpr ?: expr, visited)
            }
            is IrOp.JustImm -> (expr.value as? JSValue.Function)?.method as? AbcMethod
            else -> null
        }
    }

    /**
     * 构建 [endIndex] 之前的寄存器到表达式的映射，用于父类回溯。
     */
    private fun buildRegMap(items: List<Asm.AsmItem>, endIndex: Int): Map<FunSimCtx.RegId, IrOp.Expression> {
        val map = mutableMapOf<FunSimCtx.RegId, IrOp.Expression>()
        for (j in 0 until endIndex) {
            val op = items[j].irOp
            if (op is IrOp.AssignReg) {
                map[op.left] = op.right
            }
        }
        return map
    }

    /**
     * 从 AsmItem 列表创建 CodeSegment.Linear。
     */
    private fun createLinear(items: List<Asm.AsmItem>, originalNext: Int): CodeSegment.Linear {
        require(items.isNotEmpty()) { "Cannot create empty Linear block" }
        val first = items.first()
        val last = items.last()
        val next = if (last.next == null) originalNext else last.nextOffset
        return CodeSegment.Linear(first, items.size, next)
    }

}
