package me.yricky.oh.abcd.decompiler.structure

import me.yricky.oh.abcd.decompiler.CodeSegment
import me.yricky.oh.abcd.decompiler.structure.statement.DoWhileStatement
import me.yricky.oh.abcd.decompiler.structure.statement.IfStatement
import me.yricky.oh.abcd.decompiler.structure.statement.WhileStatement

/**
 * 结构化分析核心算法
 * 基于控制流规约（Structuring by Reduction）
 * 将 CFG 逐步折叠为结构化的控制流（if/while/do-while/逻辑表达式）
 */
class StructureAnalysis(
    private val regionGraph: RegionGraph<Region>,
    private val entryRegion: Region,
    private val lastRegion: Region
) {
    private lateinit var doms: DominatorGraph<Region>
    private val unresolvedCycles = mutableSetOf<Region>()
    private var iterationCount = 0
    private val maxIterations = 3000
    private val maxNodes = 500

    /**
     * 尾节点复制放大因子上限。
     * 当 `predecessorCount * tailStatementsSize` 超过此值时，
     * 放弃复制，改为保留共享尾节点并附加注释，防止输出指数级膨胀。
     */
    private val maxTailDuplicationFactor = 100

    /**
     * 执行结构化分析
     * @return 最终的单一 Region（如果成功）
     */
    fun analyze(): Region {
        // 初始化支配树
        doms = DominatorGraph(regionGraph, entryRegion)
        
        // 预处理
        removeSingleJumpRegions()
        simplifyConditionalSequences()

        // 主循环：反复规约直到图只剩一个节点
        while (regionGraph.nodes.size > 1 || isCyclic(regionGraph.nodes.first())) {
            iterationCount++
            if (iterationCount > maxIterations) {
                throw IllegalStateException("Structure analysis exceeded max iterations ($maxIterations)")
            }
            if (regionGraph.nodes.size > maxNodes) {
                throw IllegalStateException("Too many regions (${regionGraph.nodes.size}), exceeds limit ($maxNodes)")
            }

            // 标准化：无后继的节点标记为 Tail
            for (node in regionGraph.nodes) {
                if (node.type != Region.RegionType.Tail && regionGraph.outDegree(node) == 0) {
                    node.type = Region.RegionType.Tail
                }
            }

            val originNodeCount = regionGraph.nodes.size
            var result = false

            // 尝试无环规约
            for (region in postOrderTraversal()) {
                if (reduceAcyclic(region)) {
                    result = true
                    break
                }
            }

            // 如果无环规约失败，尝试有环规约
            if (!result) {
                for (region in postOrderTraversal()) {
                    if (isCyclic(region)) {
                        if (reduceCyclic(region)) {
                            result = true
                            break
                        }
                    }
                }
            }

            // 如果仍未规约
            if (!result) {
                val newNodeCount = regionGraph.nodes.size
                if (originNodeCount == newNodeCount && newNodeCount > 1) {
                    // 尝试处理未解决的循环
                    if (unresolvedCycles.isNotEmpty()) {
                        for (cycle in unresolvedCycles) {
                            val loopNodes = LoopFinder(regionGraph, cycle, doms).loopNodes
                            result = refineLoop(cycle, loopNodes)
                            unresolvedCycles.clear()
                            break
                        }
                    }
                    
                    // 如果仍然失败，尝试尾节点合并
                    if (!result) {
                        for (region in postOrderTraversal()) {
                            if (coalesceTailRegion(region)) {
                                result = true
                                break
                            }
                        }
                    }
                    
                    // 最后的手段：修复不可规约图
                    if (!result && !fixIrreducibleByTailRegion()) {
                        throw IllegalStateException(
                            "Cannot reduce the graph for region: $entryRegion"
                        )
                    }
                }
            }

            // 更新支配树
            doms = DominatorGraph(regionGraph, entryRegion)
        }

        return regionGraph.nodes.first()
    }

    /**
     * 删除仅含无条件跳转的线性区域
     */
    private fun removeSingleJumpRegions() {
        while (true) {
            var result = false
            for (region in postOrderTraversal()) {
                if (region.type == Region.RegionType.Linear) {
                    val block = region.block
                    if (block is CodeSegment.JumpMark && region.statements.size == 1) {
                        val successor = getOnlySuccessor(region)
                        if (successor != null) {
                            replacePredecessors(region, successor)
                            regionGraph.removeNode(region)
                            result = true
                            break
                        }
                    }
                }
            }
            if (!result) break
        }
    }

    /**
     * 简化条件序列（识别 && / || 逻辑短路）
     */
    private fun simplifyConditionalSequences() {
        // TODO: 实现逻辑短路识别
        // 需要识别：
        // 1. condition -> condition (same dest) => ||
        // 2. condition -> condition (dest = next) => &&
    }

    /**
     * 无环规约
     */
    private fun reduceAcyclic(region: Region): Boolean {
        return when (region.type) {
            Region.RegionType.Condition -> reduceIfRegion(region)
            Region.RegionType.Linear -> reduceSequence(region)
            Region.RegionType.Tail -> false
        }
    }

    /**
     * 有环规约
     */
    private fun reduceCyclic(region: Region): Boolean {
        val successors = regionGraph.successors(region).toList()
        
        // 自环 -> while(true)
        if (successors.size == 1 && successors[0] == region) {
            val bodyRegion = Region("${region.name}_body", Region.RegionType.Linear)
            bodyRegion.statements.addAll(region.statements)
            region.type = Region.RegionType.Tail
            region.clearStatements()
            region.addStatement(WhileStatement(null, bodyRegion))
            regionGraph.removeEdge(region, region)
            return true
        }

        // 尝试序列规约
        if (successors.size == 1 && reduceSequence(region)) {
            return true
        }

        // 检查回边
        for (succ in successors) {
            if (succ == region) {
                // 自环已在上面处理
                continue
            }
            val linearSucc = getOnlySuccessor(succ)
            if (linearSucc == region && regionGraph.inDegree(succ) == 1) {
                // while 循环
                if (!regionGraph.edgeValue(region, succ)!!) {
                    region.expInvert()
                }
                region.type = Region.RegionType.Linear
                region.clearStatements()
                region.addStatement(WhileStatement(region, succ))
                regionGraph.removeEdge(region, succ)
                regionGraph.removeEdge(succ, region)
                regionGraph.removeNode(succ)
                return true
            }
        }

        // 记录未解决的循环
        if (regionGraph.outDegree(region) > 0) {
            unresolvedCycles.add(region)
        }
        return false
    }

    /**
     * 规约 if 区域
     */
    private fun reduceIfRegion(region: Region): Boolean {
        val pair = getDestNextPair(region) ?: return false
        val (destRegion, nextRegion) = pair

        val destSucc = getOnlySuccessor(destRegion)
        val nextSucc = getOnlySuccessor(nextRegion)

        // if-then
        if (nextRegion == destSucc) {
            region.addStatement(IfStatement(region, null, nextRegion))
            regionGraph.removeEdge(region, nextRegion)
            if (regionGraph.inDegree(nextRegion) == 0) {
                regionGraph.removeNode(nextRegion)
            }
            region.type = Region.RegionType.Linear
            return true
        }

        // if-then (reverse)
        if (destRegion == nextSucc) {
            region.addStatement(IfStatement(region, destRegion, null))
            regionGraph.removeEdge(region, destRegion)
            if (regionGraph.inDegree(destRegion) == 0) {
                regionGraph.removeNode(destRegion)
            }
            region.type = Region.RegionType.Linear
            return true
        }

        // if-else
        if (destSucc != null && destSucc == nextSucc) {
            region.addStatement(IfStatement(region, destRegion, nextRegion))
            regionGraph.removeEdge(region, nextRegion)
            regionGraph.removeEdge(region, destRegion)
            if (regionGraph.inDegree(nextRegion) == 0) {
                regionGraph.removeNode(nextRegion)
            }
            if (regionGraph.inDegree(destRegion) == 0) {
                regionGraph.removeNode(destRegion)
            }
            regionGraph.putEdgeValue(region, destSucc, false)
            region.type = Region.RegionType.Linear
            return true
        }

        // if-else (both tail)
        if (destRegion.type == Region.RegionType.Tail && nextRegion.type == Region.RegionType.Tail) {
            region.addStatement(IfStatement(region, destRegion, nextRegion))
            regionGraph.removeEdge(region, nextRegion)
            regionGraph.removeEdge(region, destRegion)
            if (regionGraph.inDegree(nextRegion) == 0) {
                regionGraph.removeNode(nextRegion)
            }
            if (regionGraph.inDegree(destRegion) == 0) {
                regionGraph.removeNode(destRegion)
            }
            region.type = Region.RegionType.Tail
            return true
        }

        // if-then (dest is tail, e.g. if(cond) return/break; then continue)
        if (destRegion.type == Region.RegionType.Tail) {
            region.addStatement(IfStatement(region, destRegion, null))
            regionGraph.removeEdge(region, destRegion)
            if (regionGraph.inDegree(destRegion) == 0) {
                regionGraph.removeNode(destRegion)
            }
            region.type = Region.RegionType.Linear
            return true
        }

        // if-then (next is tail, e.g. if(!cond) return/break; then continue)
        if (nextRegion.type == Region.RegionType.Tail) {
            region.expInvert()
            region.addStatement(IfStatement(region, nextRegion, null))
            regionGraph.removeEdge(region, nextRegion)
            if (regionGraph.inDegree(nextRegion) == 0) {
                regionGraph.removeNode(nextRegion)
            }
            region.type = Region.RegionType.Linear
            return true
        }

        return false
    }

    /**
     * 规约序列
     */
    private fun reduceSequence(region: Region): Boolean {
        val nextRegion = getOnlySuccessor(region) ?: return false
        if (regionGraph.inDegree(nextRegion) != 1 || nextRegion == entryRegion) {
            return false
        }

        region.type = nextRegion.type
        if (nextRegion.type == Region.RegionType.Condition) {
            region.conditionalExp = nextRegion.conditionalExp
        }
        region.addRegion(nextRegion)
        regionGraph.removeEdge(region, nextRegion)
        regionGraph.replacePredecessor(nextRegion, region)
        if (nextRegion.isTryCatchHelperRegion) {
            region.isTryCatchHelperRegion = true
        }
        regionGraph.removeNode(nextRegion)
        return true
    }

    /**
     * 精化循环
     */
    private fun refineLoop(head: Region, loopNodes: Set<Region>): Boolean {
        // 简化版本：尝试将循环规约为 while/do-while
        for (latch in regionGraph.predecessors(head)) {
            if (latch !in loopNodes) continue
            if (!doms.dominatesStrictly(head, latch)) continue
            
            // 找到 latch，尝试规约
            val latchSuccs = regionGraph.successors(latch).toList()
            
            if (latchSuccs.size == 1 && latchSuccs[0] == head) {
                // do-while 循环
                if (!regionGraph.edgeValue(latch, head)!!) {
                    latch.expInvert()
                }
                val bodyRegion = Region("${latch.name}_body", Region.RegionType.Linear)
                bodyRegion.statements.addAll(latch.statements)
                latch.type = Region.RegionType.Linear
                latch.clearStatements()
                latch.addStatement(DoWhileStatement(bodyRegion))
                regionGraph.removeEdge(latch, head)
                regionGraph.removeEdge(head, latch)
                return true
            }
        }
        
        return false
    }

    /**
     * 合并尾节点
     */
    private fun coalesceTailRegion(region: Region): Boolean {
        val successors = regionGraph.successors(region).toList()
        
        if (successors.size != 2 || region.type != Region.RegionType.Condition) {
            return false
        }
        
        val el = successors[0]
        val th = successors[1]
        
        if (el.type == Region.RegionType.Tail && regionGraph.inDegree(el) == 1) {
            region.addStatement(IfStatement(region, el, null))
            regionGraph.removeEdge(region, el)
            regionGraph.removeNode(el)
            region.type = Region.RegionType.Linear
            return true
        }
        
        if (th.type == Region.RegionType.Tail && regionGraph.inDegree(th) == 1) {
            region.expInvert()
            region.addStatement(IfStatement(region, th, null))
            regionGraph.removeEdge(region, th)
            regionGraph.removeNode(th)
            region.type = Region.RegionType.Linear
            return true
        }
        
        return false
    }

    /**
     * 修复不可规约图（通过尾节点复制）
     */
    private fun fixIrreducibleByTailRegion(): Boolean {
        val tailRegions = regionGraph.nodes
            .filter { it.type == Region.RegionType.Tail && regionGraph.inDegree(it) > 1 }
            .toMutableList()

        if (tailRegions.isEmpty()) return false

        // 按入度排序，选择入度最高的
        tailRegions.sortByDescending { regionGraph.inDegree(it) }
        val region = tailRegions.first()

        val predecessors = regionGraph.predecessors(region).toList()
        val tailSize = estimateRegionSize(region)
        val duplicationFactor = predecessors.size * tailSize

        // 如果复制会导致输出过度膨胀，放弃本次修复，让上层抛出结构化失败
        // 从而避免生成指数级膨胀的代码，也避免在复制过程中消耗大量内存
        if (duplicationFactor > maxTailDuplicationFactor) {
            return false
        }

        var nodeIndex = 0

        for (predecessor in predecessors) {
            val value = regionGraph.edgeValue(predecessor, region) ?: false
            val copyNode = region.copy()
            copyNode.name = "${copyNode.name}_${nodeIndex++}"
            regionGraph.addNode(copyNode)
            regionGraph.putEdgeValue(predecessor, copyNode, value)
        }

        regionGraph.removeNode(region)
        return true
    }

    /**
     * 估算 Region 的大小（语句数），用于限制尾节点复制
     */
    private fun estimateRegionSize(region: Region): Int {
        var size = region.statements.size
        for (stmt in region.statements) {
            size += when (stmt) {
                is IfStatement -> estimateRegionSize(stmt.thenBranch ?: Region("", Region.RegionType.Linear)) +
                        estimateRegionSize(stmt.elseBranch ?: Region("", Region.RegionType.Linear))
                is WhileStatement -> estimateRegionSize(stmt.body)
                is DoWhileStatement -> estimateRegionSize(stmt.body)
                else -> 0
            }
        }
        return size.coerceAtLeast(1)
    }

    // ========== 辅助方法 ==========

    private fun postOrderTraversal(): List<Region> {
        val visited = mutableSetOf<Region>()
        val result = mutableListOf<Region>()
        
        fun dfs(node: Region) {
            if (node in visited) return
            visited.add(node)
            for (succ in regionGraph.successors(node)) {
                dfs(succ)
            }
            result.add(node)
        }
        
        for (node in regionGraph.nodes) {
            dfs(node)
        }
        return result
    }

    private fun isCyclic(node: Region): Boolean {
        for (pred in regionGraph.predecessors(node)) {
            if (pred == node || isBackEdge(pred, node)) {
                return true
            }
        }
        return false
    }

    private fun isBackEdge(from: Region, to: Region): Boolean {
        return doms.dominatesStrictly(to, from)
    }

    private fun getOnlySuccessor(node: Region): Region? {
        val succs = regionGraph.successors(node)
        return if (succs.size == 1) succs.first() else null
    }

    private fun getDestNextPair(node: Region): Pair<Region, Region>? {
        if (regionGraph.outDegree(node) != 2) return null
        
        val successors = regionGraph.successors(node).toList()
        
        return if (node.conditionalExp) {
            // 条件为真时跳转到第一个后继
            Pair(successors[0], successors[1])
        } else {
            // 条件为真时跳转到第二个后继
            Pair(successors[1], successors[0])
        }
    }

    private fun replacePredecessors(oldNode: Region, newNode: Region) {
        for (pred in regionGraph.predecessors(oldNode).toList()) {
            val value = regionGraph.edgeValue(pred, oldNode) ?: false
            regionGraph.removeEdge(pred, oldNode)
            regionGraph.putEdgeValue(pred, newNode, value)
        }
    }
}
