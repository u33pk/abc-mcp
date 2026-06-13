package me.yricky.oh.abcd.isa.util

import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.abcd.isa.asmArgs
import me.yricky.oh.abcd.isa.asmComment
import me.yricky.oh.abcd.isa.asmName

/**
 * 生成单条指令的完整反汇编文本，包含操作码、参数和注释。
 *
 * 例如：
 * ```
 * [0x1234] ldobjbyname v0, "userAgent" // 128:12
 * ```
 */
fun Asm.AsmItem.fullDisasmLine(): String {
    val args = asmArgs(listOf(BaseInstParser))
        .map { pair -> pair.second?.text ?: "?" }
        .joinToString(", ")
    val comment = asmComment
    val base = buildString {
        append("[0x${codeOffset.toString(16).padStart(4, '0')}]")
        append(" ")
        append(asmName)
        if (args.isNotBlank()) {
            append(" ")
            append(args)
        }
    }
    return if (comment.isNotBlank()) "$base // $comment" else base
}
