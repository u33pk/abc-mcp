package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.decompiler.behaviour.FunSimCtx
import me.yricky.oh.abcd.decompiler.behaviour.IrOp

/**
 * 词法环境名称解析工具
 *
 * 维护词法环境栈，并将 [ldlexvar]/[stlexvar] 使用的 (lvl, slot) 映射为
 * [newlexenvwithname] 中携带的真实变量名。
 */
object LexEnvNameResolver {

    /**
     * 计算每个指令位置之前的词法环境栈快照。
     * 返回的列表长度为 [ops.size]，其中 result[i] 表示执行 ops[i] 之前的栈状态。
     */
    fun buildLexEnvStacks(ops: List<IrOp>): List<List<List<String?>>> {
        val result = ArrayList<List<List<String?>>>(ops.size)
        val stack = mutableListOf<List<String?>>()
        for (op in ops) {
            result.add(stack.toList())
            when (op) {
                is IrOp.NewLexEnv -> stack.add(List<String?>(op.size) { null })
                is IrOp.NewLexEnvWithName -> stack.add(op.names.toList())
                is IrOp.PopLexEnv -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> {}
            }
        }
        return result
    }

    /**
     * 根据词法环境栈解析 (lvl, slot) 对应的变量名。
     * @param stack 当前词法环境栈，内层环境位于列表末尾
     * @param lvl 环境层级，0 表示最内层
     * @param slot 槽位索引
     * @return 变量名，如果找不到则返回 null
     */
    fun resolveLexName(stack: List<List<String?>>, lvl: Int, slot: Int): String? {
        val envIndex = stack.size - 1 - lvl
        if (envIndex < 0 || envIndex >= stack.size) return null
        val env = stack[envIndex]
        return env.getOrNull(slot)
    }

    /**
     * 判断指定 IR 节点是否是词法环境操作（创建或销毁环境）
     */
    fun isLexEnvOp(op: IrOp): Boolean = op is IrOp.NewLexEnv || op is IrOp.NewLexEnvWithName || op is IrOp.PopLexEnv
}

/**
 * 从词法环境寄存器 ID 中解码 (lvl, slot)
 */
fun FunSimCtx.RegId.lexLvlSlot(): Pair<Int, Int>? {
    if (value and FunSimCtx.RegId.MASK != FunSimCtx.RegId.MASK_LEX) return null
    val low = (value and 0xffffffff).toInt()
    val lvl = (low ushr 16) and 0xffff
    val slot = low and 0xffff
    return lvl to slot
}
