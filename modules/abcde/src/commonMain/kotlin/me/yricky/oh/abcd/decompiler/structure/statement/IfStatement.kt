package me.yricky.oh.abcd.decompiler.structure.statement

import me.yricky.oh.abcd.decompiler.structure.Decompilable
import me.yricky.oh.abcd.decompiler.structure.DecompileContext
import me.yricky.oh.abcd.decompiler.structure.Region

/**
 * if/then/else 语句
 * @param condition 条件区域
 * @param thenBranch then 分支（可选）
 * @param elseBranch else 分支（可选）
 */
class IfStatement(
    val condition: Region,
    val thenBranch: Region?,
    val elseBranch: Region?
) : Decompilable {
    override fun decompile(ctx: DecompileContext): String {
        val sb = StringBuilder()
        val indent = "    ".repeat(ctx.indent)
        
        // 生成条件
        sb.append("${indent}if (/* condition at ${condition.name} */) {\n")
        
        // 生成 then 分支
        if (thenBranch != null) {
            for (stmt in thenBranch.statements) {
                sb.append(stmt.decompile(ctx.indent()))
            }
        }
        
        // 生成 else 分支
        if (elseBranch != null) {
            sb.append("${indent}} else {\n")
            for (stmt in elseBranch.statements) {
                sb.append(stmt.decompile(ctx.indent()))
            }
        }
        
        sb.append("${indent}}\n")
        return sb.toString()
    }
}
