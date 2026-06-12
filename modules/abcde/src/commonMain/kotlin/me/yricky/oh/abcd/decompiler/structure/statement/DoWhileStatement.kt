package me.yricky.oh.abcd.decompiler.structure.statement

import me.yricky.oh.abcd.decompiler.structure.Decompilable
import me.yricky.oh.abcd.decompiler.structure.DecompileContext
import me.yricky.oh.abcd.decompiler.structure.Region

/**
 * do-while 循环语句
 * @param body 循环体区域（包含条件）
 */
class DoWhileStatement(
    val body: Region
) : Decompilable {
    override fun decompile(ctx: DecompileContext): String {
        val sb = StringBuilder()
        val indent = "    ".repeat(ctx.indent)
        
        sb.append("${indent}do {\n")
        for (stmt in body.statements) {
            sb.append(stmt.decompile(ctx.indent()))
        }
        sb.append("${indent}} while (/* condition */);\n")
        
        return sb.toString()
    }
}
