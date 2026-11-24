package com.flashlink.app

import java.io.ByteArrayOutputStream

object OpticalProtocol {
    const val BIT_DEFAULT = 200L
    const val START_MS = 1200L
    const val GUARD_MS = 400L
    const val HEADER = 13
    const val CHUNK = 128
    const val META = 0
    const val DATA = 1

    data class Packet(val type:Int, val id:Long, val index:Int, val total:Int, val payload:ByteArray)

    fun bits(p:Packet):List<Boolean> {
        require(p.payload.size <= CHUNK)
        val o=ByteArrayOutputStream()
        o.write(0x46); o.write(0x4c); o.write(p.type)
        put32(o,p.id); put16(o,p.index); put16(o,p.total); put16(o,p.payload.size)
        o.write(p.payload)
        val body=o.toByteArray(); val c=crc(body)
        o.write(c ushr 8); o.write(c and 255)
        return o.toByteArray().flatMap { b -> (7 downTo 0).map { ((b.toInt() ushr it) and 1)==1 } }
    }

    fun parse(b:ByteArray):Packet? {
        if(b.size<HEADER+2 || b[0]!=0x46.toByte() || b[1]!=0x4c.toByte()) return null
        val n=u16(b,11)
        if(n !in 0..CHUNK || b.size!=HEADER+n+2) return null
        val got=((b[b.size-2].toInt() and 255) shl 8) or (b.last().toInt() and 255)
        if(crc(b.copyOfRange(0,b.size-2))!=got) return null
        return Packet(b[2].toInt() and 255,u32(b,3),u16(b,7),u16(b,9),b.copyOfRange(HEADER,HEADER+n))
    }
    fun u16(b:ByteArray,i:Int)=((b[i].toInt() and 255) shl 8) or (b[i+1].toInt() and 255)
    private fun put16(o:ByteArrayOutputStream,n:Int){o.write(n ushr 8);o.write(n and 255)}
    private fun put32(o:ByteArrayOutputStream,n:Long){for(s in 24 downTo 0 step 8)o.write(((n ushr s) and 255).toInt())}
    private fun u32(b:ByteArray,i:Int)=((b[i].toLong() and 255) shl 24) or ((b[i+1].toLong() and 255) shl 16) or ((b[i+2].toLong() and 255) shl 8) or (b[i+3].toLong() and 255)
    private fun crc(b:ByteArray):Int { var c=0xffff; for(x in b){c=c xor ((x.toInt() and 255) shl 8);repeat(8){c=if(c and 0x8000!=0)((c shl 1) xor 0x1021) and 65535 else (c shl 1) and 65535}};return c }
}
