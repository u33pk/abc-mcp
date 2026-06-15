package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.AbcClass
import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class HishModuleScanTest {

    private val abcFile = File("/home/orz/project/unitTest/HISH/hish-unsigned_abc/ets/modules.abc")

    @Test
    fun scanModuleImports() {
        if (!abcFile.exists()) { println("ABC not found"); return }
        val mmap = FileChannel.open(abcFile.toPath()).map(FileChannel.MapMode.READ_ONLY, 0, abcFile.length())
        val abc = AbcBuf(abcFile.name, wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)))

        var classesWithModule = 0
        var totalImports = 0
        var totalExports = 0

        for ((_, c) in abc.classes) {
            if (c !is AbcClass) continue
            val mi = c.moduleInfo
            if (mi == null) continue
            classesWithModule++

            val ri = mi.regularImports
            val ni = mi.namespaceImports
            val le = mi.localExports
            val ie = mi.indirectExports
            val se = mi.starExports

            if (ri.isNotEmpty() || ni.isNotEmpty() || le.isNotEmpty()) {
                println("\n=== ${c.name} ===")
                if (ri.isNotEmpty()) {
                    println("  RegularImports (${ri.size}):")
                    ri.forEach { println("    import { ${it.importName} as ${it.localName} } from \"${it.moduleRequest}\"") }
                    totalImports += ri.size
                }
                if (ni.isNotEmpty()) {
                    println("  NamespaceImports (${ni.size}):")
                    ni.forEach { println("    import * as ${it.localName} from \"${it.moduleRequest}\"") }
                    totalImports += ni.size
                }
                if (le.isNotEmpty()) {
                    println("  LocalExports (${le.size}):")
                    le.forEach { println("    export { ${it.localName} as ${it.exportName} }") }
                    totalExports += le.size
                }
                if (ie.isNotEmpty()) {
                    println("  IndirectExports (${ie.size}):")
                    ie.forEach { println("    export { ${it.importName} as ${it.exportName} } from \"${it.moduleRequest}\"") }
                    totalExports += ie.size
                }
                if (se.isNotEmpty()) {
                    println("  StarExports (${se.size}):")
                    se.forEach { println("    export * from \"${it.moduleRequest}\"") }
                    totalExports += se.size
                }
            }
        }

        println("\n=== 汇总 ===")
        println("有模块信息的类: $classesWithModule")
        println("总 import 数: $totalImports")
        println("总 export 数: $totalExports")
    }
}
