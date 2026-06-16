package me.yricky.oh.abcd.decompiler.structure.statement

import me.yricky.oh.abcd.decompiler.behaviour.FunSimCtx
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.decompiler.structure.Decompilable
import me.yricky.oh.abcd.decompiler.structure.DecompileContext
import me.yricky.oh.abcd.decompiler.structure.Region

/**
 * for-of 循环语句
 * @param iterable 可迭代对象表达式
 * @param loopVarReg 循环变量寄存器
 * @param body 循环体
 */
class ForOfStatement(
    val iterable: IrOp.Expression,
    val loopVarReg: FunSimCtx.RegId,
    val body: Region
) : Decompilable {
    override fun decompile(ctx: DecompileContext): String {
        val indent = "    ".repeat(ctx.indent)
        val sb = StringBuilder()
        sb.append("${indent}for (const <loopVar> of <iterable>) {\n")
        for (stmt in body.statements) {
            sb.append(stmt.decompile(ctx.indent()))
        }
        sb.append("${indent}}\n")
        return sb.toString()
    }
}
