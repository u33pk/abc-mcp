package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.decompiler.CodeSegment
import me.yricky.oh.abcd.decompiler.behaviour.IrOp
import me.yricky.oh.abcd.isa.Asm

/**
 * 区域图构建器
 * 将 ABCDE 的 CFG（CodeSegment.BasicBlock Map）转换为 RegionGraph<Region>
 */
class RegionGraphBuilder(
    private val codeSegments: Map<Int, CodeSegment.BasicBlock>
) {
    private val regionGraph = RegionGraph<Region>()
    private val blockToRegion = mutableMapOf<CodeSegment.BasicBlock, Region>()
    private var regionCounter = 0

    /**
     * 构建区域图
     * @return Pair<RegionGraph, entryRegion>
     */
    fun build(): Pair<RegionGraph<Region>, Region> {
        // 1. 为每个 BasicBlock 创建对应的 Region
        for ((_, block) in codeSegments) {
            val regionType = determineRegionType(block)
            val region = Region("region_${regionCounter++}", regionType, block)
            blockToRegion[block] = region
            regionGraph.addNode(region)
        }

        // 2. 建立边
        for ((_, block) in codeSegments) {
            val fromRegion = blockToRegion[block] ?: continue
            val successors = getSuccessors(block)
            
            for ((succBlock, edgeValue) in successors) {
                val toRegion = blockToRegion[succBlock] ?: continue
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

        return regionGraph to entryRegion
    }

    /**
     * 确定 Region 类型
     */
    private fun determineRegionType(block: CodeSegment.BasicBlock): Region.RegionType {
        return when (block) {
            is CodeSegment.InsCondition -> Region.RegionType.Condition
            is CodeSegment.Return -> Region.RegionType.Tail
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
     * 清理不可达节点
     */
    private fun cleanUnreachableNodes() {
        val entryBlock = codeSegments.values.firstOrNull() ?: return
        val entryRegion = blockToRegion[entryBlock] ?: return
        
        // 找出所有不可达节点
        val unreachable = mutableSetOf<Region>()
        for (node in regionGraph.nodes) {
            if (node != entryRegion && regionGraph.inDegree(node) == 0) {
                unreachable.add(node)
            }
        }
        
        // 删除不可达节点
        for (node in unreachable) {
            regionGraph.removeNode(node)
        }
    }
}
