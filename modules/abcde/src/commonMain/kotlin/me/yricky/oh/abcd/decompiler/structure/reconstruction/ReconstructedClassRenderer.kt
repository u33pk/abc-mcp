package me.yricky.oh.abcd.decompiler.structure.reconstruction

import me.yricky.oh.abcd.cfm.AbcMethod
import me.yricky.oh.abcd.cfm.argsStr
import me.yricky.oh.abcd.decompiler.structure.decodeMethodName

/**
 * 将重组后的 [ReconstructedClass] 渲染为 TypeScript class 大纲语法。
 *
 * 仅输出字段声明与 constructor/method 签名，不展开方法体，避免占用过多上下文。
 * 方法体可通过 `decompile_class` / `decompile_method` 等工具单独获取。
 */
object ReconstructedClassRenderer {

    fun render(clazz: ReconstructedClass): String {
        val sb = StringBuilder()
        val superPart = clazz.superClassName?.let { " extends $it" } ?: ""
        sb.appendLine("class ${clazz.className}$superPart {")

        for (field in clazz.fields) {
            sb.appendLine("    ${field.name}: any;")
        }
        if (clazz.fields.isNotEmpty() && (clazz.constructorMethod != null || clazz.allMethods.isNotEmpty())) {
            sb.appendLine()
        }

        val members = mutableListOf<String>()
        clazz.constructorMethod?.let {
            members.add("constructor${memberSignature(it)};")
        }
        for (method in clazz.allMethods) {
            val staticPrefix = if (method.isStatic) "static " else ""
            members.add("$staticPrefix${method.name}${memberSignature(method.method)};")
        }

        val firstStaticIndex = members.indexOfFirst { it.startsWith("static ") }
        for ((index, line) in members.withIndex()) {
            sb.appendLine("    $line")
            // 在实例成员与静态成员之间空一行，提升可读性
            if (index == firstStaticIndex - 1) {
                sb.appendLine()
            }
        }

        sb.append("}")
        return sb.toString()
    }

    /**
     * 生成方法签名，剔除方舟编译器内部传入的 FunctionObject/NewTarget/this 前缀。
     */
    private fun memberSignature(method: AbcMethod): String {
        val raw = method.argsStr()
        return when {
            raw == "(FunctionObject, NewTarget, this)" -> "()"
            raw.startsWith("(FunctionObject, NewTarget, this, ") ->
                "(" + raw.removePrefix("(FunctionObject, NewTarget, this, ")
            else -> raw
        }
    }
}
