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
     * 打开会话信息，供 list_sessions 展示
     */
    data class SessionInfo(
        val path: String,
        val classCount: Int,
        val hasXRefIndex: Boolean,
        val hasHierarchyIndex: Boolean
    )

    /**
     * 关闭指定路径的会话，释放其 xref / 类层次索引缓存。
     * @return 是否存在并已关闭该会话
     */
    fun close(path: String): Boolean {
        val removed = sessions.remove(path) != null
        xrefIndexes.remove(path)
        hierarchyIndexes.remove(path)
        return removed
    }

    /**
     * 列出所有打开的会话（仅读取文件头信息，不触发类解析）
     */
    fun listSessions(): List<SessionInfo> = sessions.map { (path, abc) ->
        SessionInfo(
            path = path,
            classCount = abc.header.numClasses,
            hasXRefIndex = xrefIndexes.containsKey(path),
            hasHierarchyIndex = hierarchyIndexes.containsKey(path)
        )
    }

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
