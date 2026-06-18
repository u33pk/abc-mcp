package me.yricky.oh.mcp.export

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HapAbcExtractorTest {

    private val hapPath = "/Users/vv/project/unitTest/hap/yingshilian-HM.hap"

    @Test
    fun `extract returns abc files`() {
        val files = HapAbcExtractor.extract(hapPath)
        assertTrue("should extract at least one ABC", files.isNotEmpty())
        files.forEach { file ->
            assertTrue("ABC file should exist: ${file.name}", file.exists())
            assertTrue("should be .abc file", file.name.endsWith(".abc", ignoreCase = true))
        }
    }
}
