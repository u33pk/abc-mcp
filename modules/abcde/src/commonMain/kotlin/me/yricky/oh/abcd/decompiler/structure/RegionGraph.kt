package me.yricky.oh.abcd.decompiler.structure

/**
 * 轻量级有向图数据结构，替代 Guava MutableValueGraph
 * 支持带边权的有向图，用于表示控制流图和区域图
 */
class RegionGraph<T> {
    private val _nodes: MutableSet<T> = mutableSetOf()
    private val _successors: MutableMap<T, MutableList<Pair<T, Boolean>>> = mutableMapOf()
    private val _predecessors: MutableMap<T, MutableList<Pair<T, Boolean>>> = mutableMapOf()

    val nodes: Set<T> get() = _nodes

    fun addNode(node: T) {
        _nodes.add(node)
        _successors.putIfAbsent(node, mutableListOf())
        _predecessors.putIfAbsent(node, mutableListOf())
    }

    fun removeNode(node: T) {
        // 移除所有相关边
        _successors[node]?.forEach { (succ, _) ->
            _predecessors[succ]?.removeAll { it.first == node }
        }
        _predecessors[node]?.forEach { (pred, _) ->
            _successors[pred]?.removeAll { it.first == node }
        }
        _successors.remove(node)
        _predecessors.remove(node)
        _nodes.remove(node)
    }

    fun putEdgeValue(from: T, to: T, value: Boolean) {
        if (from !in _nodes) addNode(from)
        if (to !in _nodes) addNode(to)
        // 避免重复边
        _successors[from]?.removeAll { it.first == to }
        _successors[from]?.add(to to value)
        _predecessors[to]?.removeAll { it.first == from }
        _predecessors[to]?.add(from to value)
    }

    fun removeEdge(from: T, to: T) {
        _successors[from]?.removeAll { it.first == to }
        _predecessors[to]?.removeAll { it.first == from }
    }

    fun successors(node: T): Set<T> {
        return _successors[node]?.map { it.first }?.toSet() ?: emptySet()
    }

    /**
     * 获取后继节点及其边值
     */
    fun successorsWithValue(node: T): List<Pair<T, Boolean>> {
        return _successors[node]?.toList() ?: emptyList()
    }

    fun predecessors(node: T): Set<T> {
        return _predecessors[node]?.map { it.first }?.toSet() ?: emptySet()
    }

    fun outDegree(node: T): Int {
        return _successors[node]?.size ?: 0
    }

    fun inDegree(node: T): Int {
        return _predecessors[node]?.size ?: 0
    }

    fun edgeValue(from: T, to: T): Boolean? {
        return _successors[from]?.find { it.first == to }?.second
    }

    fun replaceSuccessor(oldNode: T, newNode: T) {
        // 将所有指向 oldNode 的边改为指向 newNode
        _predecessors[oldNode]?.forEach { (pred, value) ->
            _successors[pred]?.removeAll { it.first == oldNode }
            _successors[pred]?.add(newNode to value)
            _predecessors[newNode]?.add(pred to value)
        }
        _predecessors[oldNode]?.clear()
    }

    fun replacePredecessor(oldNode: T, newNode: T) {
        // 将所有从 oldNode 出发的边改为从 newNode 出发
        _successors[oldNode]?.forEach { (succ, value) ->
            _predecessors[succ]?.removeAll { it.first == oldNode }
            _predecessors[succ]?.add(newNode to value)
            _successors[newNode]?.add(succ to value)
        }
        _successors[oldNode]?.clear()
    }

    fun copy(): RegionGraph<T> {
        val newGraph = RegionGraph<T>()
        _nodes.forEach { newGraph.addNode(it) }
        _successors.forEach { (from, edges) ->
            edges.forEach { (to, value) ->
                newGraph.putEdgeValue(from, to, value)
            }
        }
        return newGraph
    }
}
