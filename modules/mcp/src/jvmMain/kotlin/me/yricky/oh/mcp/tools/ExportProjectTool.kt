package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.mcp.export.HapAbcExtractor
import me.yricky.oh.mcp.export.HapResourceExporter
import me.yricky.oh.mcp.export.ProjectExporter
import me.yricky.oh.mcp.session.SessionManager
import java.io.File

/**
 * 批量导出 ABC/HAP 为工程目录。
 *
 * 输出结构：
 * - `src/`：按源文件路径组织的 class 签名。
 * - `methods/`：每个方法的反编译体（单独文件）。
 * - `res/`：HAP 资源（值资源 + 媒体/原始文件），HAP 导出时默认生成。
 * - `metadata/project.json`：导出统计与文件映射。
 * - `metadata/hierarchy.json`：类层次结构。
 * - `metadata/errors.json`：错误日志。
 * - `metadata/resources.json`：资源导出摘要（HAP 导出时）。
 *
 * 支持直接传入 `.hap` 文件，会自动提取所有 ABC 并合并导出。
 */
class ExportProjectTool(private val sessionManager: SessionManager) : Tool {

    override val name = "export_project"
    override val description = "将 ABC/HAP 批量导出为可读工程目录（class 签名 + 方法体 + 资源 + 元数据）。"
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "ABC 或 HAP 文件路径")
            }
            putJsonObject("output_dir") {
                put("type", "string")
                put("description", "输出目录，默认在文件同目录生成 {name}_exported")
            }
            putJsonObject("include_method_bodies") {
                put("type", "boolean")
                put("description", "是否导出方法体，默认 true")
            }
            putJsonObject("full_decompile") {
                put("type", "boolean")
                put("description", "是否全量反编译方法体（绕过 1000 指令早期退出和 1MB 输出预算），导出到文件时默认 true")
            }
            putJsonObject("max_output_chars") {
                put("type", "integer")
                put("description", "单方法最大输出字符数，默认 50MB")
            }
            putJsonObject("include_resources") {
                put("type", "boolean")
                put("description", "HAP 导出时是否导出资源，默认 true")
            }
            putJsonObject("parallel_decompile") {
                put("type", "boolean")
                put("description", "是否并行反编译方法体，默认 true")
            }
            putJsonObject("max_concurrency") {
                put("type", "integer")
                put("description", "并行反编译最大并发数，默认 CPU 核心数")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("path"))
        }
    }

    override fun execute(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.content ?: return "Error: path is required"
        val file = File(path)
        if (!file.exists()) return "Error: File not found: $path"

        val outputDir = args["output_dir"]?.jsonPrimitive?.content
        val includeBodies = args["include_method_bodies"]?.jsonPrimitive?.booleanOrNull ?: true
        val fullDecompile = args["full_decompile"]?.jsonPrimitive?.booleanOrNull ?: true
        val maxOutputChars = args["max_output_chars"]?.jsonPrimitive?.intOrNull
            ?: me.yricky.oh.abcd.decompiler.structure.StructuredToJs.MAX_EXPORT_OUTPUT_SIZE
        val includeResources = args["include_resources"]?.jsonPrimitive?.booleanOrNull ?: true
        val parallelDecompile = args["parallel_decompile"]?.jsonPrimitive?.booleanOrNull ?: true
        val maxConcurrency = args["max_concurrency"]?.jsonPrimitive?.intOrNull
            ?: Runtime.getRuntime().availableProcessors()

        val root = if (outputDir.isNullOrBlank()) {
            File(file.parentFile, "${file.nameWithoutExtension}_exported")
        } else {
            File(outputDir)
        }

        val (abcFiles, inputType) = try {
            when {
                path.endsWith(".hap", ignoreCase = true) -> {
                    val extracted = HapAbcExtractor.extract(file)
                    if (extracted.isEmpty()) return "Error: No ABC files found in HAP: $path"
                    extracted.map { sessionManager.getOrOpen(it.absolutePath) } to "HAP"
                }
                path.endsWith(".abc", ignoreCase = true) -> {
                    listOf(sessionManager.getOrOpen(path)) to "ABC"
                }
                else -> return "Error: Unsupported file type: $path (expected .abc or .hap)"
            }
        } catch (e: Exception) {
            return "Error: failed to open input: ${e.message}"
        }

        val result = ProjectExporter.export(
            abcFiles = abcFiles,
            outputRoot = root,
            config = ProjectExporter.Config(
                includeMethodBodies = includeBodies,
                fullDecompile = fullDecompile,
                maxOutputChars = maxOutputChars,
                parallelDecompile = parallelDecompile,
                maxConcurrency = maxConcurrency
            )
        )

        val resourceResult = if (inputType == "HAP" && includeResources) {
            HapResourceExporter.export(file, root)
        } else null

        return buildString {
            appendLine("Export completed: ${root.absolutePath}")
            appendLine("  input type: $inputType")
            appendLine("  abc files: ${abcFiles.size}")
            appendLine("  source files: ${result.sourceFiles.size}")
            appendLine("  method files: ${result.methodFiles.size}")
            appendLine("  classes: ${result.classCount}")
            appendLine("  methods: ${result.methodCount}")
            appendLine("  errors: ${result.errors.size}")
            if (resourceResult != null) {
                appendLine("  resource value files: ${resourceResult.valueFiles.size}")
                appendLine("  resource file assets: ${resourceResult.fileResources.size}")
                appendLine("  resource qualifiers: ${resourceResult.qualifierCount}")
                if (resourceResult.errors.isNotEmpty()) {
                    appendLine("  resource errors: ${resourceResult.errors.size}")
                }
            }
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
