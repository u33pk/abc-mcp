package me.yricky.oh.mcp.tools

import kotlinx.serialization.json.*
import me.yricky.oh.mcp.session.SessionManager
import org.junit.Test

class HishOutputDemoTest {

    private val sessionManager = SessionManager()
    private val registry = ToolRegistry(sessionManager)
    private val hapFile = "/home/orz/project/unitTest/HISH/hish-unsigned.hap"

    private fun callTool(name: String, vararg pairs: Pair<String, JsonElement>): String {
        val args = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
        return registry.callTool(name, args)
    }

    private fun getAbcPath(): String {
        val hapResult = callTool("open_hap", "path" to JsonPrimitive(hapFile))
        for (line in hapResult.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("-> ") && trimmed.endsWith(".abc")) {
                return trimmed.removePrefix("-> ")
            }
        }
        error("No ABC found")
    }

    @Test
    fun demoOutput() {
        val abc = getAbcPath()

        println("=" .repeat(70))
        println("1. get_class_detail (EditEmulatorContent — 有 16 个 import)")
        println("=" .repeat(70))
        println(callTool("get_class_detail",
            "path" to JsonPrimitive(abc),
            "class_name" to JsonPrimitive("&entry/src/main/ets/components/EditEmulatorContent&")
        ))

        println("\n" + "=" .repeat(70))
        println("2. decompile_method — func_main_0 (模块初始化)")
        println("=" .repeat(70))
        println(callTool("decompile_method",
            "path" to JsonPrimitive(abc),
            "class_name" to JsonPrimitive("&entry/src/main/ets/components/EditEmulatorContent&"),
            "method_name" to JsonPrimitive("func_main_0")
        ))

        println("\n" + "=" .repeat(70))
        println("3. decompile_method — 有 import 的普通方法")
        println("=" .repeat(70))
        println(callTool("decompile_method",
            "path" to JsonPrimitive(abc),
            "class_name" to JsonPrimitive("&entry/src/main/ets/components/EditEmulatorContent&"),
            "method_name" to JsonPrimitive("aboutToAppear")
        ))
    }
}
