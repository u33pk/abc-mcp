package me.yricky.oh.hapde

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import me.yricky.oh.common.toByteArray
import me.yricky.oh.common.wrapAsLEByteBuf
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSSignedData
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class HapFileInfoTest{
    val file = File("/home/orz/project/unitTest/kazumi/Kazumi_ohos_2.1.5_unsigned.hap")
    val mmap by lazy {
        Assume.assumeTrue("Test HAP file not found: $file", file.exists())
        FileChannel.open(file.toPath()).map(FileChannel.MapMode.READ_ONLY,0,file.length())
    }
    val hap by lazy { wrapAsLEByteBuf(mmap.order(ByteOrder.LITTLE_ENDIAN)) }
    @Test
    fun test(){
        val info = HapFileInfo.from(hap)?.also {
            println("cdSize:${it.centralDirectorySize}")
            println("cdOff:${it.centralDirectoryOffset}")
            println("cdEC:${it.centralDirectoryEntryCount}")
        }
        if(info != null){
            val a = try {
                HapSignBlocks.from(hap)
            } catch (e: Exception) {
                println("HAP signing block parsing skipped: ${e.message}")
                null
            } ?: return
            with(Json { prettyPrint = true }){
                println(encodeToString(JsonElement.serializer(),decodeFromString(a.getProfileContent() ?: "")).replace("\\n","\n"))
            }

            val converter = JcaX509CertificateConverter()
            val cms = CMSSignedData(a.getSignatureSchemeBlock().content.toByteArray())

            cms.certificates.getMatches(null).forEach {
                val cert = converter.getCertificate(it)
                println("cert:${cert}")
            }
        }
    }
}