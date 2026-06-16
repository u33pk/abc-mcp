package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.code.TryBlock
import me.yricky.oh.abcd.decompiler.CodeSegment

/**
 * 区域节点，表示控制流图中的一个区域
 * 三种类型：Linear（顺序）、Condition（条件分支）、Tail（出口/结束）
 */
class Region(
    var name: String,
    var type: RegionType,
    val block: CodeSegment.BasicBlock? = null
) {
    enum class RegionType {
        Linear,
        Condition,
        Tail
    }

    val statements: MutableList<Decompilable> = mutableListOf()
    var conditionalExp: Boolean = false
    var isTryCatchHelperRegion: Boolean = false
    var isLogicalRegion: Boolean = false
    var isExceptionLandingPad: Boolean = false

    /**
     * 该区域处于哪些 try 作用域内（支持嵌套）。
     */
    val activeTryBlocks: MutableList<TryBlock> = mutableListOf()

    /**
     * 如果该区域是 catch handler，记录它所属的 TryBlock 与 CatchBlock。
     */
    var catchHandler: Pair<TryBlock, TryBlock.CatchBlock>? = null

    init {
        if (block != null) {
            statements.add(LinearStatement(block))
        }
    }

    fun addStatement(statement: Decompilable) {
        statements.add(statement)
    }

    fun addFirstStatement(statement: Decompilable) {
        statements.add(0, statement)
    }

    fun addRegion(other: Region) {
        statements.addAll(other.statements)
    }

    fun clearStatements() {
        statements.clear()
    }

    fun copy(): Region {
        val copy = Region(name, type, block)
        copy.statements.clear()
        copy.statements.addAll(statements)
        copy.conditionalExp = conditionalExp
        copy.isTryCatchHelperRegion = isTryCatchHelperRegion
        copy.isLogicalRegion = isLogicalRegion
        copy.isExceptionLandingPad = isExceptionLandingPad
        copy.activeTryBlocks.addAll(activeTryBlocks)
        copy.catchHandler = catchHandler
        return copy
    }

    fun expInvert() {
        conditionalExp = !conditionalExp
    }

    override fun toString(): String = name

    fun toGraphString(): String {
        return if (block != null) "$name|$block" else name
    }
}

/**
 * 可反编译的接口
 */
interface Decompilable {
    fun decompile(ctx: DecompileContext): String
}

/**
 * 反编译上下文
 */
data class DecompileContext(
    val indent: Int = 0,
    val labels: MutableSet<String> = mutableSetOf()
) {
    fun indent(): DecompileContext = copy(indent = indent + 1)
    fun addLabel(label: String) {
        labels.add(label)
    }
}

/**
 * 线性语句，包含一个基本块
 */
class LinearStatement(val block: CodeSegment.BasicBlock) : Decompilable {
    override fun decompile(ctx: DecompileContext): String {
        // 委托给具体的代码生成器处理
        return "// linear block at ${block.item.codeOffset}"
    }
}
