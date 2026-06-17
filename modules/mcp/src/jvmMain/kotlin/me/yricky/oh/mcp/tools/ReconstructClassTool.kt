package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.decompiler.structure.StructuredDecompiler
import me.yricky.oh.abcd.decompiler.structure.reconstruction.ReconstructedClassRenderer
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.mcp.session.SessionManager

/**
 * 对 ABC 文件的 func_main_0 执行 class 重组，并按类名输出 class 语法。
 */
class ReconstructClassTool(private val sessionManager: SessionManager) : Tool {
    override val name = "reconstruct_class"
    override val description = "从 func_main_0 中重组并输出指定 ArkTS/ETS class 的语法（含字段与方法体）。"
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "ABC 文件路径")
            }
            putJsonObject("class_name") {
                put("type", "string")
                put("description", "要重组的类名（短名或全限定名）。为空时列出所有识别出的类。")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("path"))
        }
    }

    override fun execute(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.content ?: return "Error: path is required"
        val targetName = args["class_name"]?.jsonPrimitive?.content
        val abc = sessionManager.getOrOpen(path)

        // 找到包含 func_main_0 的类（通常是模块入口类）
        val entryClasses = abc.classes.values
            .filterIsInstance<AbcClass>()
            .filter { cls -> cls.methods.any { it.name == AbcClass.ENTRY_FUNC_NAME } }

        val allReconstructed = entryClasses.flatMap { cls ->
            val entry = cls.methods.firstOrNull { it.name == AbcClass.ENTRY_FUNC_NAME } ?: return@flatMap emptyList()
            val code = entry.codeItem ?: return@flatMap emptyList()
            StructuredDecompiler.reconstructClasses(Asm(code))
        }

        if (allReconstructed.isEmpty()) {
            return "No reconstructed classes found in func_main_0 of $path"
        }

        if (targetName.isNullOrBlank()) {
            val sb = StringBuilder()
            sb.appendLine("Reconstructed classes in $path:")
            allReconstructed.forEach { clazz ->
                sb.appendLine("  ${clazz.className} extends ${clazz.superClassName ?: "<none>"}  fields=${clazz.fields.size} methods=${clazz.allMethods.size}")
            }
            return sb.toString()
        }

        val clazz = allReconstructed.find {
            it.className == targetName || it.className.endsWith("/$targetName")
        } ?: return "Error: Reconstructed class not found: $targetName. Available: ${allReconstructed.map { it.className }}"

        return ReconstructedClassRenderer.render(clazz)
    }
}
