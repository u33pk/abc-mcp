package me.yricky.oh.resde

import me.yricky.oh.common.*

class ResIndexBuf(
    override val buf: LEByteBuf
) :BufOffset {
    override val offset: Int get() = 0

    val header = ResIndexHeader(buf)

    /**
     * 通过实际标签位置判断格式，比单纯比较版本字符串更鲁棒。
     * 旧格式：偏移 136 处开始 "KEYS"
     * 新格式：偏移 140 处开始 "KEYS"
     */
    private val isNewFormat: Boolean by lazy {
        val oldTag = ByteArray(TAG_LEN).also { buf.get(ResIndexHeader.SIZE, it) }
        val newTag = ByteArray(TAG_LEN).also { buf.get(ResIndexHeader.SIZE_NEW, it) }
        when {
            oldTag.contentEquals(KEYS_TAG) -> false
            newTag.contentEquals(KEYS_TAG) -> true
            else -> {
                val ver = String(header.version)
                ver.startsWith("RestoolV2")
            }
        }
    }

    /**
     * Map<IdSetOffset,List<LimitKeyConfig.KeyParam>>
     */
    private fun limitKeyConfigs(): DataAndNextOff<LimitKeyConfigs> {
        val list = HashMap<Int,List<LimitKeyConfig.KeyParam>>()
        var off = ResIndexHeader.SIZE
        repeat(header.limitKeyConfigCount){
            off +=4 // "KEYS"
            val kOffset = buf.getInt(off)
            off += 4
            val keyCount = buf.getInt(off)
            off += 4
            val param = ArrayList<LimitKeyConfig.KeyParam>(keyCount)
            repeat(keyCount){
                param.add(LimitKeyConfig.KeyParam(buf.getLong(off)))
                off += 8
            }
            list[kOffset] = param
        }
        return DataAndNextOff(list,off)
    }

    /**
     * Map<ResItemOffset,Pair<ResId,IdSetOffset>>
     */
    private fun idSetMap(limitKeyConfigs: DataAndNextOff<LimitKeyConfigs>): DataAndNextOff<Map<Int,Pair<Int,Int>>>{
        val list = mutableMapOf<Int,Pair<Int,Int>>()
        var off = limitKeyConfigs.nextOffset
        repeat(header.limitKeyConfigCount){
            val thisOffset = off
            off +=4 // "IDSS"
            val idCount = buf.getInt(off)
            off += 4
            repeat(idCount){
                val idOffset = IdSet.IdOffset(buf.getLong(off))
                list[idOffset.offset] = Pair(idOffset.id,thisOffset)
                off += 8
            }
        }
        return DataAndNextOff(list,off)
    }

    private fun parseOldFormat(): Map<Int, List<ResourceItem>> {
        val limitKeyConfigs = limitKeyConfigs()
        val idSetMap = idSetMap(limitKeyConfigs)

        val map = mutableMapOf<Int,MutableList<ResourceItem>>()
        var off = idSetMap.nextOffset
        while (off < buf.limit()){
            val thisOffset = off
            off += 4
            val resType = ResType(buf.getInt(off))
            off += 4
            val resId = buf.getInt(off)
            off += 4
            val dataSize = buf.getShort(off).toUInt().toInt()
            off += 2
            val data = ByteArray((dataSize-1).coerceAtLeast(0))
            buf.get(off,data)
            off += dataSize
            val nameSize = buf.getShort(off).toUInt().toInt()
            off += 2
            val name = ByteArray((nameSize-1).coerceAtLeast(0))
            buf.get(off,name)
            off += nameSize
            val fileName = String(name)
            val idTableOffset = idSetMap.value[thisOffset]!!
            assert(resId == idTableOffset.first)
            val keyParams = limitKeyConfigs.value[idTableOffset.second]!!
            val item = ResourceItem(
                fileName, keyParams, resType, data
            )
            map[resId]?.add(item) ?: let {
                map[resId] = mutableListOf(item)
            }
        }
        return map
    }

    /**
     * 解析 RestoolV2 6.1.0.003+ 格式。
     *
     * 文件布局（来自 OpenHarmony developtools_global_resource_tool）：
     * IndexHeaderV2 (140 bytes) = version[128] + length + keyCount + dataBlockOffset
     * KeyConfig[] (带 configId)
     * IdSetHeader（按 ResType 分组）
     * DATA 块（ResInfo + 数据池）
     */
    private fun parseNewFormat(): Map<Int, List<ResourceItem>> {
        var off = ResIndexHeader.SIZE_NEW

        // 1. KeyConfig[]
        val keyConfigs = mutableMapOf<Int, List<LimitKeyConfig.KeyParam>>()
        repeat(buf.getInt(KEY_COUNT_OFFSET)) { _ ->
            off += TAG_LEN // "KEYS"
            val configId = buf.getInt(off)
            off += 4
            val keyCount = buf.getInt(off)
            off += 4
            val params = ArrayList<LimitKeyConfig.KeyParam>(keyCount)
            repeat(keyCount) {
                params.add(LimitKeyConfig.KeyParam(buf.getLong(off)))
                off += 8
            }
            keyConfigs[configId] = params
        }

        // 2. IdSetHeader
        off += TAG_LEN // "IDSS"
        val idSetLength = buf.getInt(off)
        off += 4
        val typeCount = buf.getInt(off)
        off += 4
        // idCount 与统计有关，解析时不需要校验
        off += 4

        data class ResIndexEntry(val resId: Int, val name: String, val resType: ResType, val offset: Int)
        val resIndexes = ArrayList<ResIndexEntry>()
        repeat(typeCount) {
            val resType = ResType(buf.getInt(off))
            off += 4
            val typeLength = buf.getInt(off)
            off += 4
            val count = buf.getInt(off)
            off += 4
            repeat(count) {
                val resId = buf.getInt(off)
                off += 4
                val resOffset = buf.getInt(off)
                off += 4
                val nameLen = buf.getInt(off)
                off += 4
                val nameBytes = ByteArray(nameLen)
                buf.get(off, nameBytes)
                off += nameLen
                resIndexes.add(ResIndexEntry(resId, String(nameBytes), resType, resOffset))
            }
        }

        // 3. DATA 块：tag + length + idCount + ResInfo[]
        val dataBlockOffset = buf.getInt(DATA_BLOCK_OFFSET_OFFSET)
        var dataOff = dataBlockOffset
        dataOff += TAG_LEN // "DATA"
        val dataBlockLength = buf.getInt(dataOff)
        dataOff += 4
        val idCount = buf.getInt(dataOff)
        dataOff += 4

        // 把 ResInfo 读成 map，方便按 resId 查找
        val resInfoMap = HashMap<Int, ResInfo>(idCount)
        repeat(idCount) {
            val resId = buf.getInt(dataOff)
            dataOff += 4
            val resInfoLength = buf.getInt(dataOff)
            dataOff += 4
            val valueCount = buf.getInt(dataOff)
            dataOff += 4
            val offsets = ArrayList<Pair<Int, Int>>(valueCount)
            repeat(valueCount) {
                val configId = buf.getInt(dataOff)
                dataOff += 4
                val itemOffset = buf.getInt(dataOff)
                dataOff += 4
                offsets.add(configId to itemOffset)
            }
            resInfoMap[resId] = ResInfo(resId, resInfoLength, valueCount, offsets)
        }

        // 4. 组装 ResourceItem
        val map = mutableMapOf<Int, MutableList<ResourceItem>>()
        for (entry in resIndexes) {
            val resInfo = resInfoMap[entry.resId]
                ?: throw IllegalStateException("ResInfo not found for resId ${entry.resId}")
            for ((configId, itemOffset) in resInfo.offsets) {
                val keyParams = keyConfigs[configId]
                    ?: throw IllegalStateException("KeyConfig not found for configId $configId")
                val dataLen = buf.getShort(itemOffset).toUInt().toInt()
                val dataRaw = ByteArray(dataLen)
                buf.get(itemOffset + 2, dataRaw)
                // 与旧格式保持一致：去掉末尾的 \0 终止符
                val data = if (dataRaw.isNotEmpty() && dataRaw.last() == 0.toByte()) {
                    dataRaw.copyOf(dataRaw.size - 1)
                } else dataRaw
                val item = ResourceItem(entry.name, keyParams, entry.resType, data)
                map.getOrPut(entry.resId) { mutableListOf() }.add(item)
            }
        }
        return map
    }

    private data class ResInfo(
        val resId: Int,
        val length: Int,
        val valueCount: Int,
        val offsets: List<Pair<Int, Int>>
    )

    val resMap:Map<Int,List<ResourceItem>> by lazy {
        if (isNewFormat) parseNewFormat() else parseOldFormat()
    }

    companion object {
        private const val TAG_LEN = 4
        private const val KEY_COUNT_OFFSET = 132
        private const val DATA_BLOCK_OFFSET_OFFSET = 136
        private val KEYS_TAG = "KEYS".toByteArray()
    }
}
