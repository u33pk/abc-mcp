package me.yricky.oh.abcd.xref

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.cfm.ClassTag
import me.yricky.oh.abcd.decompiler.behaviour.FunSimCtx
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.isa.Asm

/**
 * 类层次结构索引
 *
 * 扫描一次 ABC 中所有完整类定义，建立父类、接口、子类、实现者的双向索引。
 * 对于 ArkTS/ES 编译的类，ABC 类头中通常不保存 extends 信息，因此额外从
 * `NewClass` 指令的 parent 寄存器回溯父类，以恢复继承关系。
 */
class ClassHierarchyIndex private constructor(
    private val superClassMap: Map<String, String?>,
    private val interfacesMap: Map<String, List<String>>,
    private val childrenMap: Map<String, List<String>>,
    private val implementersMap: Map<String, List<String>>
) {
    /**
     * 获取指定类的父类名，不存在或没有父类时返回 null。
     */
    fun getSuperClass(className: String): String? = superClassMap[className]

    /**
     * 获取指定类实现/继承的所有接口名。
     */
    fun getInterfaces(className: String): List<String> = interfacesMap[className] ?: emptyList()

    /**
     * 获取指定类的直接子类。
     */
    fun getDirectSubClasses(className: String): List<String> = childrenMap[className] ?: emptyList()

    /**
     * 获取指定类的所有子类（递归）。
     */
    fun getAllSubClasses(className: String): List<String> {
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(getDirectSubClasses(className))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (seen.add(current)) {
                result.add(current)
                queue.addAll(getDirectSubClasses(current))
            }
        }
        return result
    }

    /**
     * 获取直接实现指定接口的类。
     */
    fun getDirectImplementers(interfaceName: String): List<String> = implementersMap[interfaceName] ?: emptyList()

    /**
     * 获取所有实现指定接口的类（递归，包括实现类的子类）。
     */
    fun getAllImplementers(interfaceName: String): List<String> {
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(getDirectImplementers(interfaceName))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (seen.add(current)) {
                result.add(current)
                queue.addAll(getDirectSubClasses(current))
            }
        }
        return result
    }

    companion object {
        fun build(abc: AbcBuf): ClassHierarchyIndex {
            val superClassMap = mutableMapOf<String, String?>()
            val interfacesMap = mutableMapOf<String, List<String>>()
            val children = mutableMapOf<String, MutableList<String>>()
            val implementers = mutableMapOf<String, MutableList<String>>()

            // 第一遍：从类头读取父类与接口
            for (classItem in abc.classes.values.filterIsInstance<AbcClass>()) {
                val className = classItem.name

                // 父类
                val superName = (classItem.superClass as? AbcClass)?.name
                superClassMap[className] = superName
                superName?.let { children.getOrPut(it) { mutableListOf() }.add(className) }

                // 接口
                val interfaces = classItem.data.filterIsInstance<ClassTag.Interfaces>().flatMap { tag ->
                    val region = classItem.region
                    tag.indexInRegionList.mapNotNull { idx ->
                        region.classes.getOrNull(idx.toInt())?.getClass(abc)?.name
                    }
                }
                interfacesMap[className] = interfaces
                for (iface in interfaces) {
                    implementers.getOrPut(iface) { mutableListOf() }.add(className)
                }
            }

            // 第二遍：从 NewClass 指令的 parent 寄存器回溯 extends 关系
            for (classItem in abc.classes.values.filterIsInstance<AbcClass>()) {
                for (method in classItem.methods) {
                    val code = method.codeItem ?: continue
                    val asm = Asm(code)
                    val regMap = mutableMapOf<FunSimCtx.RegId, IrOp.Expression>()
                    for (item in asm.list) {
                        val op = item.irOp
                        // 先处理 NewClass：用当前寄存器映射解析 parent，避免把本条
                        // AssignReg(ACC, NewClass) 提前写入 regMap 导致 LoadReg(ACC)
                        // 回指到正在创建的类本身。
                        val newClassExpr = (op as? IrOp.AssignReg)?.right as? IrOp.NewClass
                        if (newClassExpr != null) {
                            val childName = newClassExpr.constructor.method.clazz?.name ?: continue
                            val parentName = ClassNameResolver.resolveClassName(newClassExpr.parent, regMap)
                            if (parentName != null
                                && parentName != childName
                                && parentName != "undefined"
                                && parentName != "null") {
                                // 类头没有父类时，用 NewClass 推断的父类补充
                                if (superClassMap[childName] == null) {
                                    superClassMap[childName] = parentName
                                    children.getOrPut(parentName) { mutableListOf() }.add(childName)
                                }
                            }
                        }
                        if (op is IrOp.AssignReg) {
                            regMap[op.left] = op.right
                        }
                    }
                }
            }

            // 第三遍：把 NewClass 推断出的短父类名（如导入别名 FlutterAbility）
            // 规范化为 ABC 中唯一匹配的全限定类名，便于双向查询。
            val allClassNames = abc.classes.values.filterIsInstance<AbcClass>().map { it.name }.toSet()
            val shortToFull = mutableMapOf<String, String>()
            for ((child, parent) in superClassMap) {
                if (parent == null || "/" in parent) continue
                if (parent in shortToFull) {
                    superClassMap[child] = shortToFull[parent]
                    continue
                }
                val candidates = allClassNames.filter { it.endsWith("/$parent") }
                if (candidates.size == 1) {
                    val full = candidates.first()
                    shortToFull[parent] = full
                    superClassMap[child] = full
                    // 把原来挂在短名下的子类迁移到全限定名下
                    val subs = children.remove(parent) ?: mutableListOf()
                    children.getOrPut(full) { mutableListOf() }.addAll(subs)
                }
            }

            return ClassHierarchyIndex(
                superClassMap = superClassMap,
                interfacesMap = interfacesMap,
                childrenMap = children.mapValues { it.value.toList() },
                implementersMap = implementers.mapValues { it.value.toList() }
            )
        }

        // resolveClassName 已提取到 ClassNameResolver，此处直接调用
        // （自动获得 LoadLocalModuleVar + resolveOhmUrl 支持）
    }
}
