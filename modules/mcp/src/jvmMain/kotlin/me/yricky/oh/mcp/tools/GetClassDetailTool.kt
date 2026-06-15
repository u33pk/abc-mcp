package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.abcd.decompiler.structure.decodeMethodName
import me.yricky.oh.mcp.session.SessionManager

class GetClassDetailTool(private val sessionManager: SessionManager) : Tool {
    override val name = "get_class_detail"
    override val description = "获取类的详细信息（父类、字段、方法列表）"
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "ABC 文件路径")
            }
            putJsonObject("class_name") {
                put("type", "string")
                put("description", "类名")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("path"))
            add(JsonPrimitive("class_name"))
        }
    }

    override fun execute(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.content ?: return "Error: path is required"
        val className = args["class_name"]?.jsonPrimitive?.content ?: return "Error: class_name is required"
        val abc = sessionManager.getOrOpen(path)

        val classItem = abc.classes.values.find { it.name == className }
            ?: return "Error: Class not found: $className"

        val sb = StringBuilder()
        sb.appendLine("Class: ${classItem.name}")

        if (classItem is AbcClass) {
            classItem.superClass?.let { sb.appendLine("Super: ${it.name}") }
            sb.appendLine("Fields: ${classItem.fields.size}")
            classItem.fields.forEach { field ->
                sb.appendLine("  ${field.name}: ${field.type}")
            }

            // 模块 import/export 元数据
            val mi = classItem.moduleInfo
            if (mi != null) {
                val ri = mi.regularImports
                val ni = mi.namespaceImports
                val le = mi.localExports
                if (ri.isNotEmpty() || ni.isNotEmpty()) {
                    sb.appendLine("\nImports (${ri.size + ni.size}):")
                    ri.forEach { imp ->
                        val local = imp.localName ?: "?"
                        val from = imp.moduleRequest?.str ?: "?"
                        sb.appendLine("  import { $local } from \"$from\"")
                    }
                    ni.forEach { ns ->
                        val local = ns.localName ?: "?"
                        val from = ns.moduleRequest?.str ?: "?"
                        sb.appendLine("  import * as $local from \"$from\"")
                    }
                }
                if (le.isNotEmpty()) {
                    sb.appendLine("\nExports (${le.size}):")
                    le.forEach { exp ->
                        val local = exp.localName ?: "?"
                        val export = exp.exportName ?: "?"
                        if (local == export) sb.appendLine("  export { $local }")
                        else sb.appendLine("  export { $local as $export }")
                    }
                }
            }

            sb.appendLine("\nMethods: ${classItem.methods.size}")
            classItem.methods.forEach { method ->
                val methodName = if (method is me.yricky.oh.abcd.cfm.AbcMethod) {
                    decodeMethodName(method)
                } else {
                    method.name
                }
                sb.appendLine("  $methodName")
            }
        }

        return sb.toString()
    }
}
