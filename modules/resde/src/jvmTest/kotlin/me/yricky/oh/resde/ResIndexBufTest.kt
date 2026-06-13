package me.yricky.oh.resde

import me.yricky.oh.common.wrapAsLEByteBuf
import org.junit.Assume
import org.junit.Test

import org.junit.Assert.*
import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class ResIndexBufTest {

    val file = File("/Users/vv/project/unitTest/kazumi/test_resources.index")
    val mmap by lazy {
        Assume.assumeTrue("Test resources.index not found: $file", file.exists())
        FileChannel.open(file.toPath()).map(FileChannel.MapMode.READ_ONLY,0,file.length())
    }
    val res by lazy { ResIndexBuf(wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN))) }


    @Test
    fun getHeader() {
        println(String(res.header.version))
        println("size:${res.header.fileSize}")
        println("limitKeyConfigCount:${res.header.limitKeyConfigCount}")
    }

//    @Test
//    fun getLimitKeyConfigs() {
//        File.createTempFile()
//        println("size:${res.limitKeyConfigs.size}")
//        res.limitKeyConfigs.forEach {
//            println("${String.format("0x%08X",it.idSetOffset)} ${it.keyCount} ${it.data}")
//        }
//    }

//    @Test
//    fun getIdSets(){
//        println("idSet size:${res.idSet.size}")
//        res.idSet.forEach {
//            println("${it.count} ${it.idOffsetMap}")
//        }
//    }

    @Test
    fun testResMap(){
        res.resMap.forEach { t, list ->
            println("id:${t}")
            list.forEach {
                println("  ${it.fileName} ${it.limitKey} type:${it.resType} ${it.data.asString}")
            }
        }
        println("size:${res.resMap.size}")
    }
}