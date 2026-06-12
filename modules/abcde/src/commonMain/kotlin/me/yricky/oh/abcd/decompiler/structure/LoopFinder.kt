package me.yricky.oh.abcd.decompiler.structure

/**
 * 基于支配关系的循环检测
 * 通过识别回边（back edge）来找到循环
 */
class LoopFinder<T>(
    private val graph: RegionGraph<T>,
    private val entry: T,
    private val doms: DominatorGraph<T>
) {
    private enum class NodeColor {
        Gray,  // 正在访问
        Black  // 已完成
    }

    private val nodeColorMap: MutableMap<T, NodeColor> = mutableMapOf()
    private val _loopNodes: MutableSet<T> = mutableSetOf()

    val loopNodes: Set<T> get() = _loopNodes

    init {
        // 检查 entry 的所有前驱
        for (predecessor in graph.predecessors(entry)) {
            if (doms.dominatesStrictly(entry, predecessor)) {
                // predecessor 是回边的起点（latch）
                if (predecessor !in nodeColorMap) {
                    backwardVisit(predecessor)
                }
            } else if (predecessor == entry) {
                // 自环
                _loopNodes.add(predecessor)
            }
        }
        
        // 如果 entry 被标记为 Gray，进行前向访问收集所有循环节点
        val color = nodeColorMap[entry]
        if (color == NodeColor.Gray) {
            forwardVisit(entry)
        }
    }

    /**
     * 从回边起点向后遍历，标记所有可达节点为 Gray
     */
    private fun backwardVisit(node: T) {
        nodeColorMap[node] = NodeColor.Gray
        if (node == entry) return
        for (predecessor in graph.predecessors(node)) {
            if (predecessor !in nodeColorMap) {
                backwardVisit(predecessor)
            }
        }
    }

    /**
     * 从循环头向前遍历，将 Gray 节点标记为 Black 并加入循环
     */
    private fun forwardVisit(node: T) {
        nodeColorMap[node] = NodeColor.Black
        _loopNodes.add(node)
        for (successor in graph.successors(node)) {
            if (nodeColorMap[successor] == NodeColor.Gray) {
                forwardVisit(successor)
            }
        }
    }
}
