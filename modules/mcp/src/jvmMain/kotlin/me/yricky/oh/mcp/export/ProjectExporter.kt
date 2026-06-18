package me.yricky.oh.mcp.export

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.decompiler.export.SourcePathResolver
import me.yricky.oh.abcd.decompiler.structure.StructuredDecompiler
import me.yricky.oh.abcd.decompiler.structure.StructuredToJs
import me.yricky.oh.abcd.decompiler.structure.decodeMethodName
import me.yricky.oh.abcd.decompiler.structure.reconstruction.ReconstructedClass
import me.yricky.oh.abcd.decompiler.structure.reconstruction.ReconstructedClassRenderer
import me.yricky.oh.abcd.isa.Asm
import me.yricky.oh.abcd.xref.ClassHierarchyIndex
import me.yricky.oh.abcd.xref.ClassNameResolver
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * ABC 工程批量导出器。
 *
 * 支持单个 ABC 或 HAP 中提取出的多个 ABC，按源文件路径还原为可读工程目录：
 * - `src/`：重组后的 class 签名文件（按 debug info 中的 SetFile 路径或类名回退）。
 * - `methods/`：每个方法的反编译体（单独文件，便于按需查阅）。
 * - `metadata/project.json`：导出统计与文件映射。
 * - `metadata/hierarchy.json`：类层次结构。
 * - `metadata/errors.json`：导出过程中出现的错误与降级信息。
 *
 * 方法体反编译默认使用协程并行，以加速大工程导出。
 */
object ProjectExporter {

    private val prettyJson = Json { prettyPrint = true }

    /**
     * 导出配置。
     */
    data class Config(
        val includeMethodBodies: Boolean = true,
        val methodBodyLimit: Int = Int.MAX_VALUE,
        val writeHierarchy: Boolean = true,
        val writeErrors: Boolean = true,
        /** 是否以全量模式反编译方法体（导出到文件时建议开启） */
        val fullDecompile: Boolean = false,
        /** 全量模式下的单方法输出字符上限，默认 50MB */
        val maxOutputChars: Int = StructuredToJs.MAX_EXPORT_OUTPUT_SIZE,
        /** 是否并行反编译方法体 */
        val parallelDecompile: Boolean = true,
        /** 并行反编译的最大并发数，默认 CPU 核心数 */
        val maxConcurrency: Int = Runtime.getRuntime().availableProcessors()
    )

    /**
     * 导出结果。
     */
    data class Result(
        val outputRoot: File,
        val sourceFiles: List<String>,
        val methodFiles: List<String>,
        val errors: List<ExportError>,
        val classCount: Int,
        val methodCount: Int
    )

    /**
     * 单个导出错误记录。
     */
    data class ExportError(
        val type: String,
        val className: String?,
        val methodName: String?,
        val message: String
    )

    private const val SRC_DIR = "src"
    private const val METHODS_DIR = "methods"
    private const val META_DIR = "metadata"

    /**
     * 将单个 [abc] 导出到 [outputRoot] 目录。
     */
    fun export(abc: AbcBuf, outputRoot: File, config: Config = Config()): Result {
        return export(listOf(abc), outputRoot, config)
    }

    /**
     * 将多个 [abcFiles] 合并导出到 [outputRoot] 目录。
     */
    fun export(abcFiles: List<AbcBuf>, outputRoot: File, config: Config = Config()): Result {
        require(abcFiles.isNotEmpty()) { "abcFiles must not be empty" }
        return runBlocking(Dispatchers.Default) {
            exportSuspend(abcFiles, outputRoot, config)
        }
    }

    private suspend fun exportSuspend(abcFiles: List<AbcBuf>, outputRoot: File, config: Config): Result {
        outputRoot.mkdirs()
        val srcRoot = File(outputRoot, SRC_DIR).apply { mkdirs() }
        val methodsRoot = File(outputRoot, METHODS_DIR)
        val metaRoot = File(outputRoot, META_DIR).apply { mkdirs() }

        val errors = ConcurrentLinkedQueue<ExportError>()
        val sourceFiles = ConcurrentLinkedQueue<String>()
        val methodFiles = ConcurrentLinkedQueue<String>()
        val classCount = AtomicInteger()
        val methodCount = AtomicInteger()

        val hierarchies = mutableListOf<Pair<AbcBuf, ClassHierarchyIndex>>()

        if (config.includeMethodBodies) {
            methodsRoot.mkdirs()
        }

        // 第一阶段：串行处理每个 ABC 的 class 签名、类层次结构索引、并收集方法体任务
        val methodTasks = mutableListOf<MethodTask>()

        for (abc in abcFiles) {
            val abcTag = abcShortTag(abc)

            val abcHierarchy = runCatching { ClassHierarchyIndex.build(abc) }
                .getOrElse { e ->
                    errors.add(ExportError("hierarchy_build_failed", abc.tag, null, e.message ?: "unknown"))
                    null
                }
            abcHierarchy?.let { hierarchies.add(abc to it) }

            val reconstructedByModule = mutableMapOf<String, MutableList<ReconstructedClass>>()

            for (classItem in abc.classes.values.filterIsInstance<AbcClass>()) {
                val moduleName = classItem.name
                val entry = classItem.methods.firstOrNull { it.name == AbcClass.ENTRY_FUNC_NAME }
                    ?: continue

                val code = entry.codeItem ?: continue
                val reconstructed = runCatching {
                    StructuredDecompiler.reconstructClasses(Asm(code))
                }.getOrElse { e ->
                    errors.add(ExportError("reconstruct_failed", "$abcTag/$moduleName", entry.name, e.message ?: "unknown"))
                    emptyList()
                }

                if (reconstructed.isNotEmpty()) {
                    classCount.addAndGet(reconstructed.size)
                    reconstructedByModule.getOrPut(moduleName) { mutableListOf() }.addAll(reconstructed)
                }

                if (config.includeMethodBodies) {
                    val classDirName = sanitizeDirName(ClassNameResolver.normalizeClassName(moduleName).ifBlank { moduleName })
                    val classMethodsRoot = File(methodsRoot, "$abcTag/$classDirName")
                    for (method in classItem.methods) {
                        methodTasks.add(MethodTask(abc, abcTag, moduleName, method, classMethodsRoot, config))
                    }
                }
            }

            // 写入源文件（class 签名）— 串行，避免同名源文件追加冲突
            for ((moduleName, classes) in reconstructedByModule) {
                writeSourceFile(abc, abcTag, moduleName, classes, srcRoot, sourceFiles, errors)
            }
        }

        methodCount.addAndGet(methodTasks.size)

        // 第二阶段：并行反编译并写入方法体
        if (methodTasks.isNotEmpty()) {
            val semaphore = Semaphore(config.maxConcurrency)
            coroutineScope {
                methodTasks.map { task ->
                    async {
                        semaphore.withPermit {
                            writeMethodFile(task, methodFiles, errors)
                        }
                    }
                }.awaitAll()
            }
        }

        // 第三阶段：写入元数据
        writeProjectMetadata(metaRoot, abcFiles, sourceFiles.sorted(), methodFiles.sorted(), classCount.get(), methodCount.get(), errors.sortedBy { it.toString() })
        if (config.writeHierarchy && hierarchies.isNotEmpty()) {
            writeHierarchyMetadata(metaRoot, hierarchies)
        }
        if (config.writeErrors) {
            writeErrorsMetadata(metaRoot, errors.sortedBy { it.toString() })
        }

        return Result(
            outputRoot = outputRoot,
            sourceFiles = sourceFiles.sorted(),
            methodFiles = methodFiles.sorted(),
            errors = errors.sortedBy { it.toString() },
            classCount = classCount.get(),
            methodCount = methodCount.get()
        )
    }

    private fun writeSourceFile(
        abc: AbcBuf,
        abcTag: String,
        moduleName: String,
        classes: List<ReconstructedClass>,
        srcRoot: File,
        sourceFiles: ConcurrentLinkedQueue<String>,
        errors: ConcurrentLinkedQueue<ExportError>
    ) {
        val moduleClass = abc.classes.values.filterIsInstance<AbcClass>().find { it.name == moduleName }
        if (moduleClass == null) {
            errors.add(ExportError("module_class_missing", "$abcTag/$moduleName", null, "Cannot find module class"))
            return
        }

        val relativePath = SourcePathResolver.resolve(moduleClass)
        val targetFile = File(srcRoot, relativePath)
        targetFile.parentFile?.mkdirs()

        val classBlock = buildString {
            classes.forEachIndexed { index, clazz ->
                if (index > 0) appendLine()
                appendLine("// Class: ${clazz.className}")
                if (clazz.superClassName != null) {
                    appendLine("// Super: ${clazz.superClassName}")
                }
                append(ReconstructedClassRenderer.render(clazz))
                appendLine()
            }
        }

        runCatching {
            if (targetFile.exists()) {
                targetFile.appendText(buildString {
                    appendLine()
                    appendLine("// --- Additional ABC: $abcTag ---")
                    appendLine("// Module class: $moduleName")
                    append(classBlock)
                })
            } else {
                targetFile.writeText(buildString {
                    appendLine("// Source: $relativePath")
                    appendLine("// ABC: $abcTag")
                    appendLine("// Module class: $moduleName")
                    appendLine()
                    append(classBlock)
                })
            }
            sourceFiles.add("$SRC_DIR/$relativePath")
        }.onFailure { e ->
            errors.add(ExportError("write_source_failed", "$abcTag/$moduleName", null, e.message ?: "unknown"))
        }
    }

    private data class MethodTask(
        val abc: AbcBuf,
        val abcTag: String,
        val moduleName: String,
        val method: me.yricky.oh.abcd.cfm.AbcMethod,
        val classMethodsRoot: File,
        val config: Config
    )

    private suspend fun writeMethodFile(
        task: MethodTask,
        methodFiles: ConcurrentLinkedQueue<String>,
        errors: ConcurrentLinkedQueue<ExportError>
    ) = withContext(Dispatchers.IO) {
        val decodedName = decodeMethodName(task.method)
        val safeName = sanitizeFileName(decodedName)
        task.classMethodsRoot.mkdirs()
        val methodFileName = generateUniqueFileName(task.classMethodsRoot, safeName, "js")
        val targetFile = File(task.classMethodsRoot, methodFileName)

        val body = runCatching {
            val code = task.method.codeItem
            if (code != null) {
                val asm = Asm(code)
                if (task.config.fullDecompile) {
                    val result = StructuredDecompiler.decompileFull(asm, task.config.maxOutputChars)
                    if (!result.success) {
                        errors.add(ExportError("decompile_truncated", "${task.abcTag}/${task.moduleName}", decodedName, "output exceeded ${task.config.maxOutputChars} chars"))
                    }
                    result.code
                } else {
                    StructuredDecompiler.decompile(asm, 0, task.config.methodBodyLimit)
                }
            } else {
                "// No code (abstract/native)\n"
            }
        }.getOrElse { e ->
            errors.add(ExportError("decompile_failed", "${task.abcTag}/${task.moduleName}", decodedName, e.message ?: "unknown"))
            "// Error decompiling ${task.method.name}: ${e.message}\n"
        }

        val content = buildString {
            appendLine("// ABC: ${task.abcTag}")
            appendLine("// Class: ${task.moduleName}")
            appendLine("// Method: $decodedName")
            appendLine("// Original: ${task.method.name}")
            appendLine()
            append(body)
        }

        runCatching {
            targetFile.writeText(content)
            methodFiles.add("$METHODS_DIR/${task.classMethodsRoot.relativeTo(File(task.classMethodsRoot.parentFile?.parentFile, ""))}/$methodFileName")
        }.onFailure { e ->
            errors.add(ExportError("write_method_failed", "${task.abcTag}/${task.moduleName}", decodedName, e.message ?: "unknown"))
        }
    }

    private fun writeProjectMetadata(
        metaRoot: File,
        abcFiles: List<AbcBuf>,
        sourceFiles: List<String>,
        methodFiles: List<String>,
        classCount: Int,
        methodCount: Int,
        errors: List<ExportError>
    ) {
        val json = buildJsonObject {
            putJsonArray("abcFiles") {
                abcFiles.forEach { add(it.tag) }
            }
            put("classCount", classCount)
            put("methodCount", methodCount)
            put("sourceFiles", JsonArray(sourceFiles.map { JsonPrimitive(it) }))
            put("methodFiles", JsonArray(methodFiles.map { JsonPrimitive(it) }))
            put("errorCount", errors.size)
        }
        File(metaRoot, "project.json").writeText(prettyJson.encodeToString(JsonObject.serializer(), json))
    }

    private fun writeHierarchyMetadata(
        metaRoot: File,
        hierarchies: List<Pair<AbcBuf, ClassHierarchyIndex>>
    ) {
        val json = buildJsonObject {
            putJsonArray("classes") {
                for ((abc, hierarchy) in hierarchies) {
                    for (classItem in abc.classes.values.filterIsInstance<AbcClass>()) {
                        val name = classItem.name
                        addJsonObject {
                            put("name", name)
                            put("superClass", hierarchy.getSuperClass(name))
                            putJsonArray("interfaces") { hierarchy.getInterfaces(name).forEach { add(it) } }
                            putJsonArray("directSubClasses") { hierarchy.getDirectSubClasses(name).forEach { add(it) } }
                            putJsonArray("directImplementers") { hierarchy.getDirectImplementers(name).forEach { add(it) } }
                        }
                    }
                }
            }
        }
        File(metaRoot, "hierarchy.json").writeText(prettyJson.encodeToString(JsonObject.serializer(), json))
    }

    private fun writeErrorsMetadata(metaRoot: File, errors: List<ExportError>) {
        val json = buildJsonObject {
            putJsonArray("errors") {
                errors.forEach { err ->
                    addJsonObject {
                        put("type", err.type)
                        put("className", err.className)
                        put("methodName", err.methodName)
                        put("message", err.message)
                    }
                }
            }
        }
        File(metaRoot, "errors.json").writeText(prettyJson.encodeToString(JsonObject.serializer(), json))
    }

    /**
     * 生成唯一文件名：若 [baseName].js 已存在，则追加 `_1`、`_2` ...
     */
    private fun generateUniqueFileName(dir: File, baseName: String, ext: String): String {
        var candidate = "$baseName.$ext"
        if (!File(dir, candidate).exists()) return candidate
        var i = 1
        while (true) {
            candidate = "${baseName}_$i.$ext"
            if (!File(dir, candidate).exists()) return candidate
            i++
        }
    }

    private fun abcShortTag(abc: AbcBuf): String {
        return sanitizeDirName(File(abc.tag).nameWithoutExtension.ifBlank { abc.tag })
    }

    private fun sanitizeDirName(name: String): String {
        return SourcePathResolver.sanitizePath(name.trim('/'))
            .replace("/", "_")
            .ifBlank { "_unknown" }
    }

    private fun sanitizeFileName(name: String): String {
        return SourcePathResolver.sanitizeFileName(name).ifBlank { "_unknown" }
    }
}
