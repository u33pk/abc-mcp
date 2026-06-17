package me.yricky.oh.abcd.decompiler.structure.reconstruction

import me.yricky.oh.abcd.cfm.AbcMethod
import me.yricky.oh.abcd.decompiler.behaviour.FunSimCtx

/**
 * 从 func_main_0 中重组出的 ArkTS/ETS class 信息。
 *
 * @param className 解码后的类名（如 "SimpleBrowser"）
 * @param superClassName 父类全限定名（如 "AtkTsGlobal.ViewPU"），解析失败时为 null
 * @param constructorMethod 构造器方法，未识别时为 null
 * @param bufferMethods defineclasswithbuffer 的 class literal buffer 中直接声明的方法
 * @param fields 类字段（由 getter/setter 对识别）
 * @param instanceMethods 实例方法（通过 prototype 挂载）
 * @param staticMethods 静态方法（通过 class 对象直接挂载）
 * @param newClassReg NewClass 指令结果存放的寄存器，用于追踪后续成员定义
 * @param startOffset class 块起始字节码偏移
 * @param endOffset class 块结束字节码偏移
 */
data class ReconstructedClass(
    val className: String,
    val superClassName: String?,
    val constructorMethod: AbcMethod?,
    val bufferMethods: List<ClassBufferMethod>,
    val fields: MutableList<ClassField> = mutableListOf(),
    val instanceMethods: MutableList<ClassMethod> = mutableListOf(),
    val staticMethods: MutableList<ClassMethod> = mutableListOf(),
    val newClassReg: FunSimCtx.RegId? = null,
    val startOffset: Int = 0,
    val endOffset: Int = 0
) {
    /**
     * 所有成员方法（不含字段 getter/setter）
     */
    val allMethods: List<ClassMethod>
        get() = instanceMethods + staticMethods

    /**
     * 从 buffer 方法中查找指定名称的方法
     */
    fun findBufferMethod(name: String): ClassBufferMethod? = bufferMethods.find { it.name == name }
}

/**
 * defineclasswithbuffer 的 class literal buffer 中直接编码的方法条目。
 *
 * @param name 方法名（来自 literal array 中的字符串）
 * @param method 对应的方法定义
 * @param affiliate MethodAffiliate 标志值，语义待进一步确认（0/1 等）
 */
data class ClassBufferMethod(
    val name: String,
    val method: AbcMethod,
    val affiliate: Short
)

/**
 * 重组后的类字段，通常由一对 getter/setter 方法识别而来。
 *
 * @param name 字段名
 * @param getter getter 方法，无则为 null
 * @param setter setter 方法，无则为 null
 */
data class ClassField(
    val name: String,
    val getter: AbcMethod?,
    val setter: AbcMethod?
)

/**
 * 重组后的类方法（实例或静态）。
 *
 * @param name 解码后的方法名
 * @param method 对应的方法定义
 * @param isStatic 是否为静态方法
 */
data class ClassMethod(
    val name: String,
    val method: AbcMethod,
    val isStatic: Boolean = false
)
