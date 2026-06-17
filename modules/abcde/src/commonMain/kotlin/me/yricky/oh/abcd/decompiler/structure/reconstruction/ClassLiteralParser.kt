package me.yricky.oh.abcd.decompiler.structure.reconstruction

import me.yricky.oh.abcd.cfm.AbcMethod
import me.yricky.oh.abcd.literal.LiteralArray

/**
 * 解析 defineclasswithbuffer 指令的 class literal buffer。
 *
 * 观察到的 buffer 格式（以 ArkTS/ETS 编译的类为例）：
 * ```
 * { N [
 *     str:"methodName", Method:offset, MethodAffiliate:value,
 *     ...
 *     I32:flags,
 * ]}
 * ```
 *
 * 解析结果按顺序返回 [ClassBufferMethod] 列表，尾部非方法字面量（如 I32 标志）被忽略。
 */
object ClassLiteralParser {

    /**
     * 解析 class literal array。
     *
     * @param fields defineclasswithbuffer 指令中的 literal array
     * @return 按顺序解析出的方法条目列表
     */
    fun parse(fields: LiteralArray): List<ClassBufferMethod> {
        val result = mutableListOf<ClassBufferMethod>()
        val content = fields.content
        var i = 0
        while (i + 2 < content.size) {
            val nameLit = content[i] as? LiteralArray.Literal.Str
            val methodLit = content[i + 1] as? LiteralArray.Literal.Method
            val affiliateLit = content[i + 2] as? LiteralArray.Literal.MethodAffiliate

            if (nameLit == null || methodLit == null || affiliateLit == null) {
                // 无法继续按三元组解析，退出
                break
            }

            val method = methodLit.get(fields.abc)
            if (method !is AbcMethod) {
                // 跳过非本地方法（理论上不应出现）
                i += 3
                continue
            }

            result.add(
                ClassBufferMethod(
                    name = nameLit.get(fields.abc),
                    method = method,
                    affiliate = affiliateLit.value
                )
            )
            i += 3
        }
        return result
    }

    /**
     * 从 class literal buffer 中查找构造器方法。
     *
     * 当前启发式：方法名经解码后为 "constructor" 或 scope tag 为 '='。
     * 不同编译器版本可能用 MethodAffiliate 区分构造器，此处同时保留 affiliate 信息供后续调整。
     */
    fun findConstructor(bufferMethods: List<ClassBufferMethod>): AbcMethod? {
        return bufferMethods.firstOrNull { bm ->
            val name = bm.method.name
            name == "constructor" || name.contains("#~A=#A")
        }?.method
    }
}
