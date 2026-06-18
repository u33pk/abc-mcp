package me.yricky.oh.mcp.export

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class HapResourceExporterTest {

    private val hapPath = "/Users/vv/project/unitTest/hap/yingshilian-HM.hap"

    @Test
    fun `export resources creates values and media files`() {
        val outputDir = createTempDirectory("hap_resource_test_").toFile()
        outputDir.deleteRecursively()

        val result = HapResourceExporter.export(File(hapPath), outputDir)

        assertTrue("should have value files or file resources", result.valueFiles.isNotEmpty() || result.fileResources.isNotEmpty())
        assertTrue("should have qualifiers", result.qualifierCount > 0)
        assertTrue("metadata/resources.json should exist", File(outputDir, "metadata/resources.json").exists())

        if (result.valueFiles.isNotEmpty()) {
            val valuesFile = File(outputDir, result.valueFiles.first())
            assertTrue("values file should exist", valuesFile.exists())
            val content = valuesFile.readText()
            assertTrue("values file should contain JSON object", content.contains("{"))
        }

        if (result.fileResources.isNotEmpty()) {
            val mediaFile = File(outputDir, result.fileResources.first())
            assertTrue("media file should exist", mediaFile.exists())
            assertTrue("media file should not be empty", mediaFile.length() > 0)
        }

        outputDir.deleteRecursively()
    }
}
