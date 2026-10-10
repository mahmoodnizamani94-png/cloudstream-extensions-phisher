package com.phisher98

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance, offline-native XSalsa20-Poly1305 SecretBox encryptor for VidLink.
 * Eliminates 100% of network roundtrips to third-party enc-dec APIs, bypassing
 * rate limits (HTTP 429), connection dropouts, and DNS poisoning.
 */
object VidLinkCrypto {
    private const val KEY_HEX = "c75136c5668bbfe65a7ecad431a745db68b5f381555b38d8f6c699449cf11fcd"
    private val KEY = hexStringToByteArray(KEY_HEX)
    private val ZERO_NONCE = ByteArray(24)

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun rotl(a: Int, b: Int): Int = (a shl b) or (a ushr (32 - b))

    private fun qr(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[b] = x[b] xor rotl(x[a] + x[d], 7)
        x[c] = x[c] xor rotl(x[b] + x[a], 9)
        x[d] = x[d] xor rotl(x[c] + x[b], 13)
        x[a] = x[a] xor rotl(x[d] + x[c], 18)
    }

    private fun readIntLE(buf: ByteArray, offset: Int): Int {
        return (buf[offset].toInt() and 0xff) or
                ((buf[offset + 1].toInt() and 0xff) shl 8) or
                ((buf[offset + 2].toInt() and 0xff) shl 16) or
                ((buf[offset + 3].toInt() and 0xff) shl 24)
    }

    private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xff).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xff).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xff).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun hsalsa20(key: ByteArray, input: ByteArray): ByteArray {
        val x = IntArray(16)
        x[0] = 0x61707865
        x[1] = readIntLE(key, 0)
        x[2] = readIntLE(key, 4)
        x[3] = readIntLE(key, 8)
        x[4] = readIntLE(key, 12)
        x[5] = 0x3320646e
        x[6] = readIntLE(input, 0)
        x[7] = readIntLE(input, 4)
        x[8] = readIntLE(input, 8)
        x[9] = readIntLE(input, 12)
        x[10] = 0x79622d32
        x[11] = readIntLE(key, 16)
        x[12] = readIntLE(key, 20)
        x[13] = readIntLE(key, 24)
        x[14] = readIntLE(key, 28)
        x[15] = 0x6b206574

        for (i in 0 until 10) {
            qr(x, 0, 4, 8, 12)
            qr(x, 5, 9, 13, 1)
            qr(x, 10, 14, 2, 6)
            qr(x, 15, 3, 7, 11)

            qr(x, 0, 1, 2, 3)
            qr(x, 5, 6, 7, 4)
            qr(x, 10, 11, 8, 9)
            qr(x, 15, 12, 13, 14)
        }

        val out = ByteArray(32)
        val idx = intArrayOf(0, 5, 10, 15, 6, 7, 8, 9)
        for (i in 0 until 8) {
            writeIntLE(out, i * 4, x[idx[i]])
        }
        return out
    }

    private fun salsa20Block(subkey: ByteArray, nonce8: ByteArray, counter: Long): ByteArray {
        val x = IntArray(16)
        x[0] = 0x61707865
        x[1] = readIntLE(subkey, 0)
        x[2] = readIntLE(subkey, 4)
        x[3] = readIntLE(subkey, 8)
        x[4] = readIntLE(subkey, 12)
        x[5] = 0x3320646e
        x[6] = readIntLE(nonce8, 0)
        x[7] = readIntLE(nonce8, 4)
        x[8] = (counter and 0xffffffffL).toInt()
        x[9] = ((counter ushr 32) and 0xffffffffL).toInt()
        x[10] = 0x79622d32
        x[11] = readIntLE(subkey, 16)
        x[12] = readIntLE(subkey, 20)
        x[13] = readIntLE(subkey, 24)
        x[14] = readIntLE(subkey, 28)
        x[15] = 0x6b206574

        val z = x.clone()
        for (i in 0 until 10) {
            qr(z, 0, 4, 8, 12)
            qr(z, 5, 9, 13, 1)
            qr(z, 10, 14, 2, 6)
            qr(z, 15, 3, 7, 11)

            qr(z, 0, 1, 2, 3)
            qr(z, 5, 6, 7, 4)
            qr(z, 10, 11, 8, 9)
            qr(z, 15, 12, 13, 14)
        }

        val out = ByteArray(64)
        for (i in 0 until 16) {
            writeIntLE(out, i * 4, z[i] + x[i])
        }
        return out
    }

    private fun poly1305(key32: ByteArray, msg: ByteArray): ByteArray {
        val rBytes = key32.copyOfRange(0, 16)
        rBytes[3] = (rBytes[3].toInt() and 15).toByte()
        rBytes[7] = (rBytes[7].toInt() and 15).toByte()
        rBytes[11] = (rBytes[11].toInt() and 15).toByte()
        rBytes[15] = (rBytes[15].toInt() and 15).toByte()
        rBytes[4] = (rBytes[4].toInt() and 252).toByte()
        rBytes[8] = (rBytes[8].toInt() and 252).toByte()
        rBytes[12] = (rBytes[12].toInt() and 252).toByte()

        var r = BigInteger.ZERO
        for (i in 0 until 16) {
            val b = BigInteger.valueOf(rBytes[i].toLong() and 0xffL)
            r = r.or(b.shiftLeft(i * 8))
        }

        var s = BigInteger.ZERO
        for (i in 0 until 16) {
            val b = BigInteger.valueOf(key32[16 + i].toLong() and 0xffL)
            s = s.or(b.shiftLeft(i * 8))
        }

        val p = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))
        var h = BigInteger.ZERO

        var offset = 0
        while (offset < msg.size) {
            val chunkSize = minOf(16, msg.size - offset)
            var n = BigInteger.ZERO
            for (j in 0 until chunkSize) {
                val b = BigInteger.valueOf(msg[offset + j].toLong() and 0xffL)
                n = n.or(b.shiftLeft(j * 8))
            }
            n = n.or(BigInteger.ONE.shiftLeft(chunkSize * 8))
            h = h.add(n).multiply(r).mod(p)
            offset += chunkSize
        }

        val tagInt = h.add(s).mod(BigInteger.ONE.shiftLeft(128))
        val tag = ByteArray(16)
        for (i in 0 until 16) {
            tag[i] = tagInt.shiftRight(i * 8).toLong().toByte()
        }
        return tag
    }

    /**
     * Generates `length` bytes of the XSalsa20 keystream for the given subkey/nonce.
     *
     * SecretBox consumes the first 32 bytes of block 0 as the Poly1305 one-time key, so the
     * ciphertext is `plaintext XOR keystream[32..]`. Streaming across 64-byte blocks (rather
     * than assuming a single block) keeps the primitive correct for payloads of any length
     * instead of only for media ids shorter than 24 bytes.
     */
    private fun xsalsa20Keystream(subkey: ByteArray, nonce8: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        var counter = 0L
        while (offset < length) {
            val block = salsa20Block(subkey, nonce8, counter)
            val chunk = minOf(64, length - offset)
            System.arraycopy(block, 0, out, offset, chunk)
            offset += chunk
            counter++
        }
        return out
    }

    /**
     * Encrypts TMDB media ID into the exact URL-safe token format expected by vidlink.pro API.
     *
     * Layout: `base64url( nonce[24] || poly1305-tag[16] || ciphertext )`, matching
     * libsodium's `crypto_secretbox` output (the tag is prepended to the ciphertext).
     */
    fun encryptToken(mediaId: String, timestampSeconds: Long? = null): String {
        val ts = timestampSeconds ?: ((System.currentTimeMillis() / 1000L) + 480L)
        val idBytes = mediaId.toByteArray(Charsets.UTF_8)
        val tsBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(ts).array()
        val msg = ByteArray(idBytes.size + 8)
        System.arraycopy(idBytes, 0, msg, 0, idBytes.size)
        System.arraycopy(tsBytes, 0, msg, idBytes.size, 8)

        val subkey = hsalsa20(KEY, ZERO_NONCE.copyOfRange(0, 16))
        val nonce8 = ZERO_NONCE.copyOfRange(16, 24)
        val keystream = xsalsa20Keystream(subkey, nonce8, 32 + msg.size)

        val ct = ByteArray(msg.size)
        for (i in msg.indices) {
            ct[i] = (msg[i].toInt() xor keystream[32 + i].toInt()).toByte()
        }

        val tag = poly1305(keystream.copyOfRange(0, 32), ct)
        val payload = ByteArray(24 + 16 + ct.size)
        System.arraycopy(ZERO_NONCE, 0, payload, 0, 24)
        System.arraycopy(tag, 0, payload, 24, 16)
        System.arraycopy(ct, 0, payload, 40, ct.size)

        return base64UrlEncode(payload)
    }

    /**
     * URL-safe Base64 encoding without padding, compatible with standard JVM and Android.
     */
    fun base64UrlEncode(bytes: ByteArray): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val sb = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else -1

            sb.append(chars[b0 ushr 2])
            if (b1 != -1) {
                sb.append(chars[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                if (b2 != -1) {
                    sb.append(chars[((b1 and 0x0f) shl 2) or (b2 ushr 6)])
                    sb.append(chars[b2 and 0x3f])
                } else {
                    sb.append(chars[(b1 and 0x0f) shl 2])
                }
            } else {
                sb.append(chars[(b0 and 0x03) shl 4])
            }
            i += 3
        }
        return sb.toString()
    }
}
