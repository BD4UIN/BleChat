package com.blchat.blechat

import java.io.ByteArrayOutputStream

/**
 * BLE 广播聊天协议。
 *
 * 包结构（字节）：
 *   [0]      魔数 0xBC
 *   [1]      类型：0=聊天分片/在场，1=映射表同步
 *   [2]      协议版本（收到更高版本时提示升级）
 *   [3..6]   发送者 ID（4 个 ASCII 字符）
 *   [7..8]   序列号（大端 2 字节，每条消息 +1，作为消息唯一 ID 用于分片重组与去重）
 *   [9..N]   类型相关负载
 *
 * 类型 0（聊天）负载：
 *   [0]      本机名称长度（UTF-8 字节，0 表示未命名）
 *   [1..n]   本机名称（UTF-8）
 *   [n+1]    总分片数（0 表示在场信标，无消息）
 *   [n+2]    本分片序号（0 起）
 *   [n+3..]  分片内容（UTF-8）
 *
 * 类型 1（映射表同步）负载：
 *   [0]      条目数
 *   每条：   [4 字节 ID][1 字节名称长度][名称 UTF-8]
 */
object ChatProtocol {

    const val COMPANY_ID = 0xFFFF

    private const val MAGIC: Byte = 0xBC.toByte()
    const val ID_LEN = 4
    const val TYPE_LEN = 1
    const val VERSION_LEN = 1
    const val SEQ_LEN = 2

    /** 固定头长度：魔数 + 类型 + 版本 + ID + 序列号 */
    const val HEADER_LEN = 1 + TYPE_LEN + VERSION_LEN + ID_LEN + SEQ_LEN

    /** 当前协议版本 */
    const val CURRENT_VERSION = 1

    const val TYPE_CHAT = 0
    const val TYPE_MAPPING = 1

    /** 扩展广播单包负载上限（字节） */
    const val EXTENDED_MAX_PAYLOAD = 200

    /** 分片元数据长度：总分片数 + 分片序号 */
    const val CHUNK_META_LEN = 2

    fun encodeChatChunk(
        id: String,
        version: Int,
        seq: Int,
        name: String,
        total: Int,
        index: Int,
        data: String
    ): ByteArray {
        val idB = id.padEnd(ID_LEN, ' ').substring(0, ID_LEN).toByteArray(Charsets.US_ASCII)
        val nameB = name.toByteArray(Charsets.UTF_8)
        val dataB = data.toByteArray(Charsets.UTF_8)
        val out = ByteArray(HEADER_LEN + 1 + nameB.size + CHUNK_META_LEN + dataB.size)
        out[0] = MAGIC
        out[1] = TYPE_CHAT.toByte()
        out[2] = version.toByte()
        System.arraycopy(idB, 0, out, 3, ID_LEN)
        out[3 + ID_LEN] = ((seq shr 8) and 0xFF).toByte()
        out[4 + ID_LEN] = (seq and 0xFF).toByte()
        var p = HEADER_LEN
        out[p] = nameB.size.toByte(); p++
        System.arraycopy(nameB, 0, out, p, nameB.size); p += nameB.size
        out[p] = total.toByte(); p++
        out[p] = index.toByte(); p++
        System.arraycopy(dataB, 0, out, p, dataB.size)
        return out
    }

    fun encodeMapping(
        id: String,
        version: Int,
        seq: Int,
        entries: Map<String, String>,
        maxPayload: Int
    ): ByteArray {
        val idB = id.padEnd(ID_LEN, ' ').substring(0, ID_LEN).toByteArray(Charsets.US_ASCII)
        val maxBody = maxPayload - HEADER_LEN
        val body = ByteArrayOutputStream()
        body.write(0)
        var count = 0
        for ((eid, name) in entries) {
            if (eid.isBlank() || name.isBlank()) continue
            val eidB = eid.padEnd(ID_LEN, ' ').substring(0, ID_LEN).toByteArray(Charsets.US_ASCII)
            val nameB = name.toByteArray(Charsets.UTF_8)
            if (nameB.size > 255) continue
            val entryLen = ID_LEN + 1 + nameB.size
            if (body.size() + entryLen > maxBody) break
            body.write(eidB)
            body.write(nameB.size)
            body.write(nameB)
            count++
        }
        val bodyBytes = body.toByteArray()
        bodyBytes[0] = count.toByte()

        val out = ByteArray(HEADER_LEN + bodyBytes.size)
        out[0] = MAGIC
        out[1] = TYPE_MAPPING.toByte()
        out[2] = version.toByte()
        System.arraycopy(idB, 0, out, 3, ID_LEN)
        out[3 + ID_LEN] = ((seq shr 8) and 0xFF).toByte()
        out[4 + ID_LEN] = (seq and 0xFF).toByte()
        System.arraycopy(bodyBytes, 0, out, HEADER_LEN, bodyBytes.size)
        return out
    }

    fun decode(data: ByteArray): Decoded? {
        if (data.size < HEADER_LEN) return null
        if (data[0] != MAGIC) return null
        val type = data[1].toInt() and 0xFF
        val version = data[2].toInt() and 0xFF
        val id = String(data, 3, ID_LEN, Charsets.US_ASCII).trim()
        if (id.isEmpty()) return null
        val seq = ((data[3 + ID_LEN].toInt() and 0xFF) shl 8) or (data[4 + ID_LEN].toInt() and 0xFF)
        val body = data.copyOfRange(HEADER_LEN, data.size)
        return when (type) {
            TYPE_CHAT -> decodeChat(id, version, seq, body)
            TYPE_MAPPING -> decodeMapping(id, version, seq, body)
            else -> null
        }
    }

    private fun decodeChat(id: String, version: Int, seq: Int, body: ByteArray): Decoded {
        if (body.isEmpty()) return Decoded(TYPE_CHAT, version, id, seq, "", 0, 0, "", emptyMap())
        val nameLen = body[0].toInt() and 0xFF
        var p = 1
        var name = ""
        if (nameLen > 0 && p + nameLen <= body.size) {
            name = String(body, p, nameLen, Charsets.UTF_8)
            p += nameLen
        } else {
            p += nameLen
        }
        val total = if (p < body.size) body[p].toInt() and 0xFF else 0
        p++
        val index = if (p < body.size) body[p].toInt() and 0xFF else 0
        p++
        val data = if (p < body.size) String(body, p, body.size - p, Charsets.UTF_8) else ""
        return Decoded(TYPE_CHAT, version, id, seq, name, total, index, data, emptyMap())
    }

    private fun decodeMapping(id: String, version: Int, seq: Int, body: ByteArray): Decoded {
        val entries = LinkedHashMap<String, String>()
        if (body.isNotEmpty()) {
            val count = body[0].toInt() and 0xFF
            var off = 1
            var i = 0
            while (i < count && off + ID_LEN <= body.size) {
                val eid = String(body, off, ID_LEN, Charsets.US_ASCII).trim()
                off += ID_LEN
                if (off >= body.size) break
                val len = body[off].toInt() and 0xFF
                off += 1
                if (off + len > body.size) break
                val name = String(body, off, len, Charsets.UTF_8)
                off += len
                if (eid.isNotEmpty()) entries[eid] = name
                i++
            }
        }
        return Decoded(TYPE_MAPPING, version, id, seq, "", 0, 0, "", entries)
    }

    data class Decoded(
        val type: Int,
        val version: Int,
        val id: String,
        val seq: Int,
        val name: String,           // 发送者本机名（TYPE_CHAT）
        val chunkTotal: Int,        // 总分片数（TYPE_CHAT，0=在场）
        val chunkIndex: Int,        // 本分片序号（TYPE_CHAT）
        val chunkData: String,      // 分片内容（TYPE_CHAT）
        val mapping: Map<String, String>  // 映射表（TYPE_MAPPING）
    )
}
