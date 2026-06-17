package me.yricky.oh.abcd.decompiler.structure.statement

import me.yricky.oh.abcd.decompiler.structure.Decompilable
import me.yricky.oh.abcd.decompiler.structure.DecompileContext
import me.yricky.oh.abcd.decompiler.structure.reconstruction.ReconstructedClass

/**
 * 重组后的 class/struct 声明语句。
 *
 * 在 StructuredToJs 中，该节点会被渲染为 TypeScript class 语法；
 * 方法体通过单独反编译对应 AbcMethod 生成。
 */
class ClassDeclaration(
    val clazz: ReconstructedClass
) : Decompilable {
    override fun decompile(ctx: DecompileContext): String {
        // 当前由 StructuredToJs.generateRegion 直接处理，此处仅返回占位描述
        val indent = "    ".repeat(ctx.indent)
        return "${indent}// class ${clazz.className} declaration\n"
    }
}
