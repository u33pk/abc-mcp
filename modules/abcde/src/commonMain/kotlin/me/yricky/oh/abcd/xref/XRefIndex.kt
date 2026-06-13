package me.yricky.oh.abcd.xref

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.cfm.AbcMethod
import me.yricky.oh.abcd.decompiler.structure.decodeMethodName
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.abcd.isa.calledMethods

/**
 * 交叉引用索引
 *
 * 借鉴 jadx 的 UsageInfo 设计：扫描一次全库，建立“目标 -> 引用源”的反向索引。
 * 第一阶段只实现方法调用 xref；字段和类引用后续扩展。
 */
class XRefIndex private constructor(
    /** 被调方法 -> 调用它的位置列表 */
    val methodCallers: Map<AbcMethod, List<XRefLocation>>
) {
    /**
     * 查询方法的调用者（谁调用了这个方法）
     */
    fun getCallers(method: AbcMethod): List<XRefLocation> {
        return methodCallers[method] ?: emptyList()
    }

    companion object {
        /**
         * 从 [AbcBuf] 构建交叉引用索引
         */
        fun build(abc: AbcBuf): XRefIndex {
            val callerMap = mutableMapOf<AbcMethod, MutableList<XRefLocation>>()

            for (classItem in abc.classes.values) {
                if (classItem !is AbcClass) continue
                for (method in classItem.methods) {
                    val code = method.codeItem ?: continue
                    val asm = Asm(code)
                    for (item in asm.list) {
                        for (callee in item.calledMethods) {
                            val loc = XRefLocation(
                                callerClass = classItem.name,
                                callerMethod = method.name,
                                callerDecodedName = decodeMethodName(method),
                                codeOffset = item.codeOffset
                            )
                            callerMap.getOrPut(callee) { mutableListOf() }.add(loc)
                        }
                    }
                }
            }

            return XRefIndex(
                methodCallers = callerMap.mapValues { it.value.toList() }
            )
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
