package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.code.TryBlock
import me.yricky.oh.abcd.decompiler.CodeSegment
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.isa.Asm

/**
 * 区域图构建器
 * 将 ABCDE 的 CFG（CodeSegment.BasicBlock Map）转换为 RegionGraph<Region>
 *
 * 关键改造：把 catch handler 从主控制流图剥离。catch handler 只能由异常机制进入，
 * 不是普通跳转目标，因此不加入主图，避免干扰 if/while 等结构化分析。
 */
class RegionGraphBuilder(
    private val codeSegments: Map<Int, CodeSegment.BasicBlock>,
    private val tryBlocks: List<TryBlock> = emptyList()
) {
    data class CatchHandlerInfo(
        val region: Region,
        val tryBlock: TryBlock,
        val catchBlock: TryBlock.CatchBlock
    )

    data class Result(
        val graph: RegionGraph<Region>,
        val entry: Region,
        val catchHandlers: List<CatchHandlerInfo>
    )

    private val regionGraph = RegionGraph<Region>()
    private val blockToRegion = mutableMapOf<CodeSegment.BasicBlock, Region>()
    private val catchHandlers = mutableListOf<CatchHandlerInfo>()
    private var regionCounter = 0

    /**
     * 构建区域图
     * @return Result（主图、入口 Region、catch handler 列表）
     */
    fun build(): Result {
        // 1. 为每个 BasicBlock 创建对应的 Region
        val handlerMap = tryBlocks
            .flatMap { t -> t.catchBlocks.map { it.handlerPc to (t to it) } }
            .toMap()

        for ((_, block) in codeSegments) {
            val regionType = determineRegionType(block)
            val region = Region("region_${regionCounter++}", regionType, block)

            // 标记 catch handler：只能从异常机制进入，不加入主控制流图
            handlerMap[block.item.codeOffset]?.let { (tryBlock, catchBlock) ->
                region.catchHandler = tryBlock to catchBlock
                catchHandlers.add(CatchHandlerInfo(region, tryBlock, catchBlock))
            }

            // 标记当前块处于哪些 try 范围内（catch handler 自身不算在 try 内）
            if (region.catchHandler == null) {
                val blockOff = block.item.codeOffset
                tryBlocks.forEach { tryBlock ->
                    if (tryBlock.startPc <= blockOff && blockOff < tryBlock.startPc + tryBlock.length) {
                        region.activeTryBlocks.add(tryBlock)
                    }
                }
                regionGraph.addNode(region)
            }

            blockToRegion[block] = region
        }

        // 2. 建立边（跳过 catch handler 作为源或目标）
        for ((_, block) in codeSegments) {
            val fromRegion = blockToRegion[block] ?: continue
            if (fromRegion.catchHandler != null) continue

            val successors = getSuccessors(block)

            for ((succBlock, edgeValue) in successors) {
                val toRegion = blockToRegion[succBlock] ?: continue
                if (toRegion.catchHandler != null) continue
                regionGraph.putEdgeValue(fromRegion, toRegion, edgeValue)
            }
        }

        // 3. 清理不可达节点
        cleanUnreachableNodes()

        // 4. 获取入口 Region
        val entryBlock = codeSegments.values.firstOrNull()
            ?: throw IllegalStateException("No basic blocks found")
        val entryRegion = blockToRegion[entryBlock]
            ?: throw IllegalStateException("Entry region not found")

        return Result(regionGraph, entryRegion, catchHandlers)
    }

    /**
     * 确定 Region 类型
     */
    private fun determineRegionType(block: CodeSegment.BasicBlock): Region.RegionType {
        return when (block) {
            is CodeSegment.InsCondition -> Region.RegionType.Condition
            is CodeSegment.Return -> Region.RegionType.Tail
            is CodeSegment.Throw -> Region.RegionType.Tail
            is CodeSegment.JumpMark -> {
                // 无条件跳转，检查是否是尾节点
                val irOp = block.item.irOp
                if (irOp is IrOp.Jump) {
                    // 无条件跳转，出度为 1，不是 Tail
                    Region.RegionType.Linear
                } else {
                    Region.RegionType.Linear
                }
            }
            is CodeSegment.Linear -> {
                // 检查最后一条指令
                val lastItem = block.item.asm.list.getOrNull(block.item.index + block.itemCount - 1)
                val lastIrOp = lastItem?.irOp
                when (lastIrOp) {
                    is IrOp.Return -> Region.RegionType.Tail
                    else -> Region.RegionType.Linear
                }
            }
            else -> Region.RegionType.Linear
        }
    }

    /**
     * 获取基本块的后继
     * @return List<Pair<BasicBlock, edgeValue>> edgeValue: true=条件为真/false=条件为假或fall-through
     */
    private fun getSuccessors(block: CodeSegment.BasicBlock): List<Pair<CodeSegment.BasicBlock, Boolean>> {
        val result = mutableListOf<Pair<CodeSegment.BasicBlock, Boolean>>()
        
        when (block) {
            is CodeSegment.InsCondition -> {
                // 条件跳转：两个后继
                // 1. 跳转目标（条件为真）
                val jmpTarget = codeSegments[block.jmpTo]
                if (jmpTarget != null) {
                    result.add(jmpTarget to true)
                }
                // 2. fall-through（条件为假）
                val nextBlock = codeSegments[block.next]
                if (nextBlock != null) {
                    result.add(nextBlock to false)
                }
            }
            is CodeSegment.JumpMark -> {
                // 无条件跳转：一个后继
                val irOp = block.item.irOp
                if (irOp is IrOp.Jump) {
                    val target = codeSegments[block.item.codeOffset + irOp.offset]
                    if (target != null) {
                        result.add(target to false)
                    }
                }
            }
            is CodeSegment.Return -> {
                // 返回：无后继
            }
            is CodeSegment.Throw -> {
                // throw：无普通后继（异常处理由 try-catch 表接管）
            }
            is CodeSegment.Linear -> {
                // 线性块：检查最后一条指令
                val lastItem = block.item.asm.list.getOrNull(block.item.index + block.itemCount - 1)
                val lastIrOp = lastItem?.irOp
                when (lastIrOp) {
                    is IrOp.Return -> {
                        // 返回：无后继
                    }
                    is IrOp.Jump -> {
                        // 无条件跳转
                        val target = codeSegments[lastItem.codeOffset + lastIrOp.offset]
                        if (target != null) {
                            result.add(target to false)
                        }
                    }
                    is IrOp.JumpIf -> {
                        // 条件跳转
                        val jmpTarget = codeSegments[lastItem.codeOffset + lastIrOp.offset]
                        if (jmpTarget != null) {
                            result.add(jmpTarget to true)
                        }
                        val nextBlock = codeSegments[block.next]
                        if (nextBlock != null) {
                            result.add(nextBlock to false)
                        }
                    }
                    else -> {
                        // 普通指令，fall-through 到 next
                        val nextBlock = codeSegments[block.next]
                        if (nextBlock != null) {
                            result.add(nextBlock to false)
                        }
                    }
                }
            }
        }
        
        return result
    }

    /**
     * 清理不可达节点（迭代到不动点，处理级联不可达）
     */
    private fun cleanUnreachableNodes() {
        val entryBlock = codeSegments.values.firstOrNull() ?: return
        val entryRegion = blockToRegion[entryBlock] ?: return

        do {
            val unreachable = regionGraph.nodes.filter {
                it != entryRegion && regionGraph.inDegree(it) == 0
            }
            for (node in unreachable) {
                regionGraph.removeNode(node)
            }
        } while (unreachable.isNotEmpty())
    }
}
