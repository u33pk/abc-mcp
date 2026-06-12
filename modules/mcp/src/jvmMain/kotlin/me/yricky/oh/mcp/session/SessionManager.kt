package me.yricky.oh.mcp.session

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.common.wrapAsLEByteBuf
import java.io.File
import java.nio.channels.FileChannel

class SessionManager {
    private val sessions = mutableMapOf<String, AbcBuf>()

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
}
