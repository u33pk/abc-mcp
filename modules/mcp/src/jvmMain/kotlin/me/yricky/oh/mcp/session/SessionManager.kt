package me.yricky.oh.mcp.session

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.xref.ClassHierarchyIndex
import me.yricky.oh.abcd.xref.XRefIndex
import me.yricky.oh.common.wrapAsLEByteBuf
import java.io.File
import java.nio.channels.FileChannel

class SessionManager {
    private val sessions = mutableMapOf<String, AbcBuf>()
    private val xrefIndexes = mutableMapOf<String, XRefIndex>()
    private val hierarchyIndexes = mutableMapOf<String, ClassHierarchyIndex>()

    fun open(path: String): AbcBuf {
        sessions[path]?.let { return it }

        val file = File(path)
        if (!file.exists()) throw IllegalArgumentException("File not found: $path")

        val buf = FileChannel.open(file.toPath())
            .map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        val abc = AbcBuf(path, wrapAsLEByteBuf(buf))
        sessions[path] = abc
        return abc
    }

    fun get(path: String): AbcBuf? = sessions[path]

    fun getOrOpen(path: String): AbcBuf = get(path) ?: open(path)

    /**
     * 获取指定 ABC 文件的交叉引用索引。
     * 索引会懒加载并缓存，首次调用会扫描全库方法调用关系。
     */
    fun getXRefIndex(path: String): XRefIndex {
        return xrefIndexes.getOrPut(path) {
            XRefIndex.build(getOrOpen(path))
        }
    }

    /**
     * 获取指定 ABC 文件的类层次结构索引。
     * 索引会懒加载并缓存，首次调用会扫描全库类定义。
     */
    fun getClassHierarchyIndex(path: String): ClassHierarchyIndex {
        return hierarchyIndexes.getOrPut(path) {
            ClassHierarchyIndex.build(getOrOpen(path))
        }
    }
}
