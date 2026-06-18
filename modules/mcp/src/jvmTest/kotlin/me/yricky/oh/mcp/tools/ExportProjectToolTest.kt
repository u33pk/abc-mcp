package me.yricky.oh.mcp.tools

import me.yricky.oh.mcp.session.SessionManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ExportProjectToolTest {

    private val abcPath = "/Users/vv/project/unitTest/out/defineclasswithbuffer_dynamic.abc"
    private val hapPath = "/Users/vv/project/unitTest/hap/yingshilian-HM.hap"

    @Test
    fun `export project creates source and method files`() {
        val outputDir = createTempDirectory("export_project_test_").toFile()
        outputDir.deleteRecursively()

        val tool = ExportProjectTool(SessionManager())
        val result = tool.execute(
            buildJsonObject {
                put("path", abcPath)
                put("output_dir", outputDir.absolutePath)
                put("include_method_bodies", true)
            }
        )

        assertTrue("should report success", result.startsWith("Export completed:"))
        assertTrue("src dir should exist", File(outputDir, "src").exists())
        assertTrue("methods dir should exist", File(outputDir, "methods").exists())
        assertTrue("project.json should exist", File(outputDir, "metadata/project.json").exists())
        assertTrue("hierarchy.json should exist", File(outputDir, "metadata/hierarchy.json").exists())
        assertTrue("errors.json should exist", File(outputDir, "metadata/errors.json").exists())

        val srcFiles = File(outputDir, "src").walkTopDown().filter { it.isFile }.toList()
        val methodFiles = File(outputDir, "methods").walkTopDown().filter { it.isFile }.toList()

        assertTrue("should produce at least one source file", srcFiles.isNotEmpty())
        assertTrue("should produce at least one method file", methodFiles.isNotEmpty())

        val sourceContent = srcFiles.first().readText()
        assertTrue("source should contain class keyword", sourceContent.contains("class "))

        outputDir.deleteRecursively()
    }

    @Test
    fun `export project without method bodies skips methods dir`() {
        val outputDir = createTempDirectory("export_project_no_body_test_").toFile()
        outputDir.deleteRecursively()

        val tool = ExportProjectTool(SessionManager())
        val result = tool.execute(
            buildJsonObject {
                put("path", abcPath)
                put("output_dir", outputDir.absolutePath)
                put("include_method_bodies", false)
            }
        )

        assertTrue("should report success", result.startsWith("Export completed:"))
        assertTrue("src dir should exist", File(outputDir, "src").exists())
        assertFalse("methods dir should not exist", File(outputDir, "methods").exists())

        outputDir.deleteRecursively()
    }

    @Test
    fun `export project from hap merges all abc`() {
        val outputDir = createTempDirectory("export_project_hap_test_").toFile()
        outputDir.deleteRecursively()

        val tool = ExportProjectTool(SessionManager())
        val result = tool.execute(
            buildJsonObject {
                put("path", hapPath)
                put("output_dir", outputDir.absolutePath)
                put("include_method_bodies", true)
                put("full_decompile", true)
            }
        )

        assertTrue("should report success", result.startsWith("Export completed:"))
        assertTrue("result should mention HAP", result.contains("input type: HAP"))
        assertTrue("src dir should exist", File(outputDir, "src").exists())
        assertTrue("methods dir should exist", File(outputDir, "methods").exists())
        assertTrue("project.json should exist", File(outputDir, "metadata/project.json").exists())

        outputDir.deleteRecursively()
    }

    @Test
    fun `export project from hap includes resources by default`() {
        val outputDir = createTempDirectory("export_project_hap_res_test_").toFile()
        outputDir.deleteRecursively()

        val tool = ExportProjectTool(SessionManager())
        val result = tool.execute(
            buildJsonObject {
                put("path", hapPath)
                put("output_dir", outputDir.absolutePath)
                put("include_method_bodies", false)
            }
        )

        assertTrue("should report success", result.startsWith("Export completed:"))
        assertTrue("res dir should exist", File(outputDir, "res").exists())
        assertTrue("resources.json should exist", File(outputDir, "metadata/resources.json").exists())

        outputDir.deleteRecursively()
    }

    @Test
    fun `export project from hap can skip resources`() {
        val outputDir = createTempDirectory("export_project_hap_no_res_test_").toFile()
        outputDir.deleteRecursively()

        val tool = ExportProjectTool(SessionManager())
        val result = tool.execute(
            buildJsonObject {
                put("path", hapPath)
                put("output_dir", outputDir.absolutePath)
                put("include_method_bodies", false)
                put("include_resources", false)
            }
        )

        assertTrue("should report success", result.startsWith("Export completed:"))
        assertFalse("res dir should not exist", File(outputDir, "res").exists())

        outputDir.deleteRecursively()
    }
}
