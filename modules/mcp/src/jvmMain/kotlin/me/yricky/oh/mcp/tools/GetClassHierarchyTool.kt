package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.mcp.session.SessionManager

class GetClassHierarchyTool(private val sessionManager: SessionManager) : Tool {
    override val name = "get_class_hierarchy"
    override val description = "查询 ABC 文件中指定类的层次结构（父类、接口、子类、实现者）"
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
        val classItem = abc.findClassByName(className)
            ?: return "Error: Class not found: $className"

        if (classItem !is AbcClass) return "Error: $className is not a full class definition"

        val index = sessionManager.getClassHierarchyIndex(path)
        val sb = StringBuilder()
        sb.appendLine("// Class hierarchy for $className")
        sb.appendLine()

        val superClass = index.getSuperClass(className)
        sb.appendLine("Super class: ${superClass ?: "(none)"}")

        val interfaces = index.getInterfaces(className)
        sb.appendLine("Interfaces: ${if (interfaces.isEmpty()) "(none)" else interfaces.joinToString(", ")}")

        val directSubClasses = index.getDirectSubClasses(className)
        val allSubClasses = index.getAllSubClasses(className)
        sb.appendLine("Direct subclasses: ${directSubClasses.size}")
        if (directSubClasses.isNotEmpty()) {
            directSubClasses.forEachIndexed { idx, name ->
                sb.appendLine("  ${idx + 1}. $name")
            }
        }
        sb.appendLine("All subclasses: ${allSubClasses.size}")
        if (allSubClasses.isNotEmpty()) {
            allSubClasses.forEachIndexed { idx, name ->
                sb.appendLine("  ${idx + 1}. $name")
            }
        }

        if (classItem.accessFlags.isInterface) {
            val directImplementers = index.getDirectImplementers(className)
            val allImplementers = index.getAllImplementers(className)
            sb.appendLine("Direct implementers: ${directImplementers.size}")
            if (directImplementers.isNotEmpty()) {
                directImplementers.forEachIndexed { idx, name ->
                    sb.appendLine("  ${idx + 1}. $name")
                }
            }
            sb.appendLine("All implementers: ${allImplementers.size}")
            if (allImplementers.isNotEmpty()) {
                allImplementers.forEachIndexed { idx, name ->
                    sb.appendLine("  ${idx + 1}. $name")
                }
            }
        }

        return sb.toString().trim()
    }
}
