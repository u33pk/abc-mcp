package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.mcp.export.ProjectExporter
import me.yricky.oh.mcp.session.SessionManager
import java.io.File

/**
 * 批量导出 ABC 文件为工程目录。
 *
 * 输出结构：
 * - `src/`：按源文件路径组织的 class 签名。
 * - `methods/`：每个方法的反编译体（单独文件）。
 * - `metadata/project.json`：导出统计与文件映射。
 * - `metadata/hierarchy.json`：类层次结构。
 * - `metadata/errors.json`：错误日志。
 */
class ExportProjectTool(private val sessionManager: SessionManager) : Tool {

    override val name = "export_project"
    override val description = "将 ABC 文件批量导出为可读工程目录（class 签名 + 方法体 + 元数据）。"
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "ABC 文件路径")
            }
            putJsonObject("output_dir") {
                put("type", "string")
                put("description", "输出目录，默认在 ABC 同目录生成 {name}_exported")
            }
            putJsonObject("include_method_bodies") {
                put("type", "boolean")
                put("description", "是否导出方法体，默认 true")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("path"))
        }
    }

    override fun execute(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.content ?: return "Error: path is required"
        val outputDir = args["output_dir"]?.jsonPrimitive?.content
        val includeBodies = args["include_method_bodies"]?.jsonPrimitive?.booleanOrNull ?: true

        val abc = try {
            sessionManager.getOrOpen(path)
        } catch (e: Exception) {
            return "Error: failed to open ABC: ${e.message}"
        }

        val root = if (outputDir.isNullOrBlank()) {
            val base = File(path).nameWithoutExtension
            File(File(path).parentFile, "${base}_exported")
        } else {
            File(outputDir)
        }

        val result = ProjectExporter.export(
            abc = abc,
            outputRoot = root,
            config = ProjectExporter.Config(includeMethodBodies = includeBodies)
        )

        return buildString {
            appendLine("Export completed: ${root.absolutePath}")
            appendLine("  source files: ${result.sourceFiles.size}")
            appendLine("  method files: ${result.methodFiles.size}")
            appendLine("  classes: ${result.classCount}")
            appendLine("  methods: ${result.methodCount}")
            appendLine("  errors: ${result.errors.size}")
            if (result.errors.isNotEmpty()) {
                appendLine()
                appendLine("Errors:")
                result.errors.take(10).forEach { err ->
                    appendLine("  [${err.type}] ${err.className ?: ""}.${err.methodName ?: ""}: ${err.message}")
                }
                if (result.errors.size > 10) {
                    appendLine("  ... and ${result.errors.size - 10} more")
                }
            }
        }
    }
}
