package me.yricky.oh.abcd.decompiler.structure.statement

import me.yricky.oh.abcd.decompiler.behaviour.FunSimCtx
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.decompiler.structure.Decompilable
import me.yricky.oh.abcd.decompiler.structure.DecompileContext
import me.yricky.oh.abcd.decompiler.structure.Region

/**
 * for-in 循环语句
 * @param obj 被遍历对象表达式
 * @param keyReg 键寄存器
 * @param body 循环体
 */
class ForInStatement(
    val obj: IrOp.Expression,
    val keyReg: FunSimCtx.RegId,
    val body: Region
) : Decompilable {
    override fun decompile(ctx: DecompileContext): String {
        val indent = "    ".repeat(ctx.indent)
        val sb = StringBuilder()
        sb.append("${indent}for (const <key> in <obj>) {\n")
        for (stmt in body.statements) {
            sb.append(stmt.decompile(ctx.indent()))
        }
        sb.append("${indent}}\n")
        return sb.toString()
    }
}
