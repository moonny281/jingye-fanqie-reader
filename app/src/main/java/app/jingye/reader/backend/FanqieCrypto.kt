package app.jingye.reader.backend

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object FanqieCrypto {
    const val AID = "1967"
    const val LICENSE_ID = "1611921764"
    const val SDK_VERSION = "v04.04.05-ov-android"
    const val SDK_VERSION_INT = 134744640L
    const val CALL_TYPE = 738L

    val SIGN_KEY: ByteArray = hexToBytes("ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c")
    val SHARED_KEY: ByteArray = byteArrayOf(
        172.toByte(), 37, 198.toByte(), 125, 221.toByte(), 143.toByte(), 56, 193.toByte(),
        179.toByte(), 122, 35, 72, 130.toByte(), 142.toByte(), 34, 46
    )
    private val FIXED_STRING: ByteArray = Base64.decode(
        "TdTC5rgxYgkOUrPHpnM7pByyRiuCmrWKGWs521cXdST0m69/COjWjSanLjfBqVovHwWlGJKu8pSXMrYqOKrdWA==",
        Base64.DEFAULT
    )

    private val LOW_RAND = byteArrayOf(0xF2.toByte(), 0x81.toByte())
    private val HIGH_RAND = byteArrayOf(0x61, 0x6F)
    private val XOR_PREFIX = byteArrayOf(
        0xF2.toByte(), 0xF7.toByte(), 0xFC.toByte(), 0xFF.toByte(),
        0xF2.toByte(), 0xF7.toByte(), 0xFC.toByte(), 0xFF.toByte()
    )
    private val ARGUS_HEADER = byteArrayOf(
        0xA6.toByte(), 0x6E, 0xAD.toByte(), 0x9F.toByte(),
        0x77, 0x01, 0xD0.toByte(), 0x0C, 0x18
    )

    private val rng = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { rng.nextBytes(it) }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)
    fun md5Hex(data: ByteArray): String = bytesToHex(md5(data))
    fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

    fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun rotl32(x: Int, n: Int): Int {
        val s = n and 31
        return if (s == 0) x else (x shl s) or (x ushr (32 - s))
    }

    private fun sm3P0(x: Int): Int = x xor rotl32(x, 9) xor rotl32(x, 17)
    private fun sm3P1(x: Int): Int = x xor rotl32(x, 15) xor rotl32(x, 23)

    fun sm3(data: ByteArray): ByteArray {
        val bitLen = data.size.toLong() * 8L
        val out = ByteArrayOutputStream()
        out.write(data)
        out.write(0x80)
        while ((out.size() % 64) != 56) {
            out.write(0x00)
        }
        val lenBuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(bitLen).array()
        out.write(lenBuf)
        val padded = out.toByteArray()

        val state = intArrayOf(
            0x7380166F, 0x4914B2B9, 0x172442D7, 0xDA8A0600.toInt(),
            0xA96F30BC.toInt(), 0x163138AA, 0xE38DEE4D.toInt(), 0xB0FB0E4E.toInt()
        )

        val w = IntArray(68)
        val w1 = IntArray(64)

        var off = 0
        while (off < padded.size) {
            val bb = ByteBuffer.wrap(padded, off, 64).order(ByteOrder.BIG_ENDIAN)
            for (i in 0 until 16) {
                w[i] = bb.int
            }
            for (i in 16 until 68) {
                w[i] = sm3P1(w[i - 16] xor w[i - 9] xor rotl32(w[i - 3], 15)) xor
                    rotl32(w[i - 13], 7) xor w[i - 6]
            }
            for (i in 0 until 64) {
                w1[i] = w[i] xor w[i + 4]
            }

            var a = state[0]
            var b = state[1]
            var c = state[2]
            var d = state[3]
            var e = state[4]
            var f = state[5]
            var g = state[6]
            var h = state[7]

            for (i in 0 until 64) {
                val t = if (i <= 15) 0x79CC4519 else 0x7A879D8A
                val ss1 = rotl32(rotl32(a, 12) + e + rotl32(t, i), 7)
                val ss2 = ss1 xor rotl32(a, 12)
                val ff = if (i <= 15) (a xor b xor c) else ((a and b) or (a and c) or (b and c))
                val gg = if (i <= 15) (e xor f xor g) else ((e and f) or (e.inv() and g))
                val tt1 = ff + d + ss2 + w1[i]
                val tt2 = gg + h + ss1 + w[i]
                d = c
                c = rotl32(b, 9)
                b = a
                a = tt1
                h = g
                g = rotl32(f, 19)
                f = e
                e = sm3P0(tt2)
            }

            state[0] = state[0] xor a
            state[1] = state[1] xor b
            state[2] = state[2] xor c
            state[3] = state[3] xor d
            state[4] = state[4] xor e
            state[5] = state[5] xor f
            state[6] = state[6] xor g
            state[7] = state[7] xor h

            off += 64
        }

        val res = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        for (v in state) res.putInt(v)
        return res.array()
    }

    private fun rol64(v: Long, n: Int): Long = (v shl n) or (v ushr (64 - n))
    private fun ror64(v: Long, n: Int): Long = (v shl (64 - n)) or (v ushr n)

    private fun simonKeyExpand(key: LongArray): LongArray {
        val z4 = 0x3DC94C3A046D678BL
        val k = LongArray(72)
        for (i in 0 until 4) k[i] = key[i]
        for (i in 4 until 72) {
            var tmp = ror64(k[i - 1], 3)
            tmp = tmp xor k[i - 3]
            tmp = tmp xor ror64(tmp, 1)
            val bit = (z4 ushr ((i - 4) % 62)) and 1L
            k[i] = k[i - 4].inv() xor tmp xor bit xor 3L
        }
        return k
    }

    private fun simonEncrypt(ptLow: Long, ptHigh: Long, key: LongArray): Pair<Long, Long> {
        val k = simonKeyExpand(key)
        var x = ptLow
        var y = ptHigh
        for (i in 0 until 72) {
            val tmp = y
            val f = rol64(y, 1) and rol64(y, 8)
            y = x xor f xor rol64(y, 2) xor k[i]
            x = tmp
        }
        return x to y
    }

    private fun speckKeySchedule(key: ByteArray): LongArray {
        val numWords = key.size / 8
        val ks = LongArray(34)
        val ls = LongArray(34 + numWords)
        val bb = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN)
        ks[0] = bb.long
        for (i in 1 until numWords) {
            ls[i - 1] = bb.long
        }
        for (x in 0 until 33) {
            val rsX = (ls[x] shl 56) or (ls[x] ushr 8)
            val newX = x.toLong() xor (rsX + ks[x])
            val lsY = (ks[x] ushr 61) or (ks[x] shl 3)
            ls[x + numWords - 1] = newX
            ks[x + 1] = newX xor lsY
        }
        return ks
    }

    fun speckEncrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val pad = 16 - (plaintext.size % 16)
        val padded = ByteArray(plaintext.size + pad)
        System.arraycopy(plaintext, 0, padded, 0, plaintext.size)
        for (i in plaintext.size until padded.size) padded[i] = pad.toByte()

        val ks = speckKeySchedule(key)
        val out = ByteBuffer.allocate(padded.size).order(ByteOrder.LITTLE_ENDIAN)
        val inBuf = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until padded.size step 16) {
            var y = inBuf.long
            var x = inBuf.long
            for (k in ks) {
                val rotX = (x shl 56) or (x ushr 8)
                x = k xor (rotX + y)
                val rotY = (y ushr 61) or (y shl 3)
                y = x xor rotY
            }
            out.putLong(y)
            out.putLong(x)
        }
        return out.array()
    }

    private class ProtobufWriter {
        private val buf = ByteArrayOutputStream()

        private fun rawVarint(value: Long): ProtobufWriter {
            var v = value
            while ((v and -0x80L) != 0L) {
                buf.write((v.toInt() and 0x7F) or 0x80)
                v = v ushr 7
            }
            buf.write(v.toInt() and 0x7F)
            return this
        }

        private fun key(field: Int, wire: Int): ProtobufWriter =
            rawVarint(((field shl 3) or wire).toLong())

        fun varint(field: Int, value: Long): ProtobufWriter =
            key(field, 0).rawVarint(value)

        fun bytes(field: Int, data: ByteArray): ProtobufWriter {
            key(field, 2).rawVarint(data.size.toLong())
            buf.write(data)
            return this
        }

        fun string(field: Int, value: String): ProtobufWriter =
            bytes(field, value.toByteArray(Charsets.UTF_8))

        fun message(field: Int, build: (ProtobufWriter) -> Unit): ProtobufWriter {
            val sub = ProtobufWriter()
            build(sub)
            return bytes(field, sub.toBytes())
        }

        fun toBytes(): ByteArray = buf.toByteArray()
    }

    private fun sm3Prefix6(data: ByteArray): ByteArray = sm3(data).copyOfRange(0, 6)

    fun getArgus(query: String, xssStub: String, timestamp: Long): String {
        val params = HashMap<String, String>()
        for (part in query.split("&")) {
            val idx = part.indexOf('=')
            if (idx >= 0) {
                params[part.substring(0, idx)] = part.substring(idx + 1)
            }
        }
        val deviceId = params["device_id"] ?: ""
        val versionName = params["version_name"] ?: ""

        val bodyHash = sm3Prefix6(if (xssStub.isEmpty()) ByteArray(16) else hexToBytes(xssStub.take(32)))
        val queryHash = sm3Prefix6(if (query.isEmpty()) ByteArray(16) else query.toByteArray(Charsets.UTF_8))

        val pb = ProtobufWriter()
        pb.varint(1, 0x20200929L * 2L)
        pb.varint(2, 2L)
        pb.varint(3, (rng.nextInt() and 0x7FFFFFFF).toLong())
        pb.string(4, AID)
        pb.string(5, deviceId)
        pb.string(6, LICENSE_ID)
        pb.string(7, versionName)
        pb.string(8, SDK_VERSION)
        pb.varint(9, SDK_VERSION_INT)
        pb.bytes(10, ByteArray(8))
        pb.varint(11, 0L)
        pb.varint(12, timestamp * 2L)
        pb.bytes(13, bodyHash)
        pb.bytes(14, queryHash)
        pb.message(15) { sub ->
            sub.varint(1, 1L)
            sub.varint(2, 1L)
            sub.varint(3, 1L)
            sub.varint(7, 3348294860L)
        }
        pb.string(16, "")
        pb.string(20, "none")
        pb.varint(21, CALL_TYPE)
        pb.message(23) { sub ->
            sub.string(1, "NX551J")
            sub.varint(2, 8196L)
            sub.varint(4, 2162219008L)
        }
        pb.varint(25, 2L)

        val rawPb = pb.toBytes()
        val pad = 16 - (rawPb.size % 16)
        val protobuf = ByteArray(rawPb.size + pad)
        System.arraycopy(rawPb, 0, protobuf, 0, rawPb.size)
        for (i in rawPb.size until protobuf.size) protobuf[i] = pad.toByte()

        val digestInput = SIGN_KEY + LOW_RAND + HIGH_RAND + SIGN_KEY
        val digest = sm3(digestInput)
        val digestBuf = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        val simonKey = LongArray(4) { digestBuf.long }

        val encrypted = ByteBuffer.allocate(protobuf.size).order(ByteOrder.LITTLE_ENDIAN)
        val pbBuf = ByteBuffer.wrap(protobuf).order(ByteOrder.LITTLE_ENDIAN)
        for (off in 0 until protobuf.size step 16) {
            val low = pbBuf.long
            val high = pbBuf.long
            val (eLow, eHigh) = simonEncrypt(low, high, simonKey)
            encrypted.putLong(eLow)
            encrypted.putLong(eHigh)
        }

        val data = XOR_PREFIX + encrypted.array()
        for (i in 8 until data.size) {
            data[i] = (data[i].toInt() xor data[i % 8].toInt()).toByte()
        }
        data.reverse()

        val plaintext = ARGUS_HEADER + data + HIGH_RAND
        val aesKey = md5(SIGN_KEY.copyOfRange(0, 16))
        val aesIv = md5(SIGN_KEY.copyOfRange(16, 32))
        val ciphertext = aesCbcEncrypt(aesKey, aesIv, plaintext)
        return Base64.encodeToString(LOW_RAND + ciphertext, Base64.NO_WRAP)
    }

    fun getLadon(timestamp: Long): String {
        val rand4 = randomBytes(4)
        val plaintext = "$timestamp-$LICENSE_ID-$AID".toByteArray(Charsets.UTF_8)
        val key = md5Hex(rand4 + AID.toByteArray(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        val enc = speckEncrypt(key, plaintext)
        return Base64.encodeToString(rand4 + enc, Base64.NO_WRAP)
    }

    fun signRequest(url: String, body: ByteArray? = null): Map<String, String> {
        val rawQuery = URL_QUERY_RE.find(url)?.groupValues?.getOrNull(1) ?: ""
        val xssStub = if (body != null && body.isNotEmpty()) md5Hex(body) else ""
        val nowMs = System.currentTimeMillis()
        val ts = nowMs / 1000L
        val headers = LinkedHashMap<String, String>()
        headers["x-argus"] = getArgus(rawQuery, xssStub, ts)
        headers["x-ladon"] = getLadon(ts)
        headers["x-khronos"] = ts.toString()
        headers["x-ss-req-ticket"] = nowMs.toString()
        if (xssStub.isNotEmpty()) {
            headers["X-SS-STUB"] = xssStub
        }
        return headers
    }

    private val URL_QUERY_RE = Regex("^[^?]+\\?([^#]*)")

    private fun gzipDeterministic(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val compressedBody = ByteArrayOutputStream()
        val tmp = ByteArray(4096)
        while (!deflater.finished()) {
            val count = deflater.deflate(tmp)
            if (count > 0) compressedBody.write(tmp, 0, count)
        }
        deflater.end()

        val crc = CRC32()
        crc.update(data)

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0xff.toByte()))
        out.write(compressedBody.toByteArray())
        val trailer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(crc.value.toInt())
            .putInt(data.size)
            .array()
        out.write(trailer)
        return out.toByteArray()
    }

    fun ttEncrypt(data: ByteArray): ByteArray {
        val rand32 = randomBytes(32)
        val hashValue = sha512(sha512(rand32) + FIXED_STRING)
        val key = hashValue.copyOfRange(0, 16)
        val iv = hashValue.copyOfRange(16, 32)
        val compressed = gzipDeterministic(data)
        val encrypted = aesCbcEncrypt(key, iv, sha512(compressed) + compressed)
        return byteArrayOf(116, 99, 5, 16, 0, 0) + rand32 + encrypted
    }
}
