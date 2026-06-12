package me.yricky.oh.abcd.decompiler.structure.statement

import me.yricky.oh.abcd.decompiler.structure.Decompilable
import me.yricky.oh.abcd.decompiler.structure.DecompileContext
import me.yricky.oh.abcd.decompiler.structure.Region

/**
 * while 循环语句
 * @param condition 条件区域（可选，null 表示 while(true)）
 * @param body 循环体区域
 */
class WhileStatement(
    val condition: Region?,
    val body: Region
) : Decompilable {
    override fun decompile(ctx: DecompileContext): String {
        val sb = StringBuilder()
        val indent = "    ".repeat(ctx.indent)
        
        if (condition == null) {
            sb.append("${indent}while (true) {\n")
        } else {
            sb.append("${indent}while (/* condition at ${condition.name} */) {\n")
        }
        
        for (stmt in body.statements) {
            sb.append(stmt.decompile(ctx.indent()))
        }
        
        sb.append("${indent}}\n")
        return sb.toString()
    }
}
