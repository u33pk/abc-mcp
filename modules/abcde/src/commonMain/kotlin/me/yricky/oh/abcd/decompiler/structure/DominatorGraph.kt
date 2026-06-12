package me.yricky.oh.abcd.decompiler.structure

/**
 * 支配树计算
 * 基于 Cooper-Harvey-Kennedy 的迭代算法
 * 计算直接支配者（idom）和支配边界（dominance frontier）
 */
class DominatorGraph<T>(
    private val graph: RegionGraph<T>,
    entry: T
) {
    // 直接支配者映射：node -> immediate dominator
    private val idoms: MutableMap<T, T?> = mutableMapOf()
    // 支配边界：node -> set of nodes in its dominance frontier
    private val domFrontier: MutableMap<T, MutableList<T>> = mutableMapOf()
    // 反向后序编号
    private var reversePostOrder: MutableMap<T, Int> = mutableMapOf()

    init {
        build(graph, entry)
        idoms[entry] = null
        buildDominanceFrontiers()
    }

    /**
     * 构建支配树
     */
    private fun build(graph: RegionGraph<T>, entryNode: T) {
        idoms[entryNode] = entryNode
        reversePostorderNumbering(graph, entryNode)
        
        // 按反向后序遍历节点
        val nodesByRPO = reversePostOrder.entries
            .sortedBy { it.value }
            .map { it.key }

        var changed: Boolean
        do {
            changed = false
            for (b in nodesByRPO) {
                if (b == entryNode) continue
                
                var newIdom: T? = null
                for (p in graph.predecessors(b)) {
                    if (p in idoms) {
                        newIdom = if (newIdom == null) {
                            p
                        } else {
                            intersect(idoms, p, newIdom)
                        }
                    }
                }
                
                val oldIdom = idoms[b]
                if ((oldIdom == null || oldIdom != newIdom) && newIdom != null) {
                    idoms[b] = newIdom
                    changed = true
                }
            }
        } while (changed)
    }

    /**
     * 计算两个节点的最近公共支配者
     */
    private fun intersect(idoms: Map<T, T?>, b1: T, b2: T): T {
        var i1 = b1
        var i2 = b2
        var count = 0
        while (i1 != i2) {
            while ((reversePostOrder[i1] ?: 0) > (reversePostOrder[i2] ?: 0)) {
                count++
                if (count > 100000) {
                    throw IllegalStateException("Dominator graph calculation timed out.")
                }
                i1 = idoms[i1] ?: break
            }
            while ((reversePostOrder[i2] ?: 0) > (reversePostOrder[i1] ?: 0)) {
                count++
                if (count > 100000) {
                    throw IllegalStateException("Dominator graph calculation timed out.")
                }
                i2 = idoms[i2] ?: break
            }
        }
        return i1
    }

    /**
     * 计算反向后序编号
     */
    private fun reversePostorderNumbering(graph: RegionGraph<T>, entryNode: T) {
        val visited = mutableSetOf<T>()
        val postOrder = mutableListOf<T>()
        
        fun dfs(node: T) {
            if (node in visited) return
            visited.add(node)
            for (succ in graph.successors(node)) {
                dfs(succ)
            }
            postOrder.add(node)
        }
        
        dfs(entryNode)
        
        // 反向后序 = 后序的逆序
        postOrder.reversed().forEachIndexed { index, node ->
            reversePostOrder[node] = index
        }
    }

    /**
     * 构建支配边界
     */
    private fun buildDominanceFrontiers() {
        // 初始化
        for (node in graph.nodes) {
            domFrontier[node] = mutableListOf()
        }

        for (bb in graph.nodes) {
            val pred = graph.predecessors(bb)
            if (pred.size < 2) continue
            
            for (p in pred) {
                var r = p
                while (r != null && r != idoms[bb]) {
                    if (bb !in (domFrontier[r] ?: emptyList())) {
                        domFrontier[r]?.add(bb)
                    }
                    r = idoms[r] ?: break
                }
            }
        }
    }

    /**
     * 判断 dominator 是否严格支配 dominated
     * 严格支配：dominator 支配 dominated，且 dominator != dominated
     */
    fun dominatesStrictly(dominator: T, dominated: T): Boolean {
        var d = dominated
        while (d in idoms && idoms[d] != null) {
            val iDom = idoms[d] ?: break
            if (iDom == dominator) {
                return true
            }
            d = iDom
        }
        return false
    }

    /**
     * 获取节点的直接支配者
     */
    fun getImmediateDominator(node: T): T? {
        return idoms[node] ?: null
    }

    /**
     * 获取节点的支配边界
     */
    fun getDominanceFrontier(node: T): List<T> {
        return domFrontier[node] ?: emptyList()
    }

    /**
     * 获取节点的反向后序编号
     */
    fun getReversePostOrder(node: T): Int {
        return reversePostOrder[node] ?: -1
    }
}
