package me.yricky.oh.mcp.tools

import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.cfm.ClassItem

/**
 * 按类名查找类，支持两种格式：
 * 1. 完整名（带 & 定界符）：`&entry/src/main/ets/pages/WebPage&`
 * 2. 路径格式（去掉 &）：`entry/src/main/ets/pages/WebPage`
 */
fun AbcBuf.findClassByName(className: String): ClassItem? {
    // 精确匹配（兼容已有完整名）
    classes.values.find { it.name == className }?.let { return it }
    // a/b/c/ClassName 格式 → 自动加 & 匹配
    if (!className.startsWith("&")) {
        classes.values.find { it.name == "&$className&" }?.let { return it }
    }
    return null
}
