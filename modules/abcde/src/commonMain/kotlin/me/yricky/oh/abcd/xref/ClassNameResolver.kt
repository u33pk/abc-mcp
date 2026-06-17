package me.yricky.oh.abcd.xref

import me.yricky.oh.abcd.decompiler.behaviour.FunSimCtx
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.decompiler.behaviour.JSValue
import me.yricky.oh.abcd.literal.OhmUrl

/**
 * 类名解析共享工具。
 *
 * 从寄存器或表达式出发，尝试解析出全限定类名。
 * 支持 `LoadExternalModule`（含 OhmUrl 各前缀）、`LoadLocalModuleVar`、`NewClass` 等来源。
 * 统一供 [XRefIndex] 和 [ClassHierarchyIndex] 使用，避免逻辑分叉。
 */
object ClassNameResolver {

    /**
     * 从寄存器出发，尝试解析出类名。
     */
    fun resolveClassName(
        regId: FunSimCtx.RegId,
        regMap: Map<FunSimCtx.RegId, IrOp.Expression>,
        visited: MutableSet<FunSimCtx.RegId> = mutableSetOf()
    ): String? {
        if (!visited.add(regId)) return null
        return resolveClassName(regMap[regId], regMap, visited)
    }

    /**
     * 从表达式出发，尝试解析出类名。
     */
    fun resolveClassName(
        expr: IrOp.Expression?,
        regMap: Map<FunSimCtx.RegId, IrOp.Expression>,
        visited: MutableSet<FunSimCtx.RegId> = mutableSetOf()
    ): String? {
        return when (expr) {
            is IrOp.JustImm -> (expr.value as? JSValue.Function)?.method?.clazz?.name
            is IrOp.LoadReg -> resolveClassName(expr.regId, regMap, visited)
            is IrOp.LoadExternalModule -> resolveOhmUrl(expr.ext.moduleRequest?.str) ?: expr.ext.localName
            is IrOp.LoadLocalModuleVar -> expr.local.exportName
            is IrOp.NewClass -> expr.constructor.method.clazz?.name
            else -> null
        }
    }

    /**
     * 从 OhmUrl 中提取全限定类名。
     *
     * `@normalized:N&&&path&` 格式在 URL 中嵌入了类的全限定路径，可直接提取。
     * 其他前缀（`@bundle:`/`@package:`/`@ohos:` 等）的 URL 只是模块路径，不含类名，
     * 返回 null，由调用方 fallback 到 importName/localName。
     */
    fun resolveOhmUrl(url: String?): String? {
        if (url == null) return null
        return when {
            url.startsWith(OhmUrl.PREFIX_NORMALIZED_NOT_CROSS_HAP_FILE) ->
                url.removePrefix(OhmUrl.PREFIX_NORMALIZED_NOT_CROSS_HAP_FILE).removeSuffix("&")
            else -> null
        }
    }

    /**
     * 规范化类名：去掉 `&` 定界符。
     * `&entry/src/main/ets/pages/WebPage&` → `entry/src/main/ets/pages/WebPage`
     */
    fun normalizeClassName(name: String): String {
        return if (name.startsWith("&") && name.endsWith("&")) {
            name.substring(1, name.length - 1)
        } else name
    }
}
