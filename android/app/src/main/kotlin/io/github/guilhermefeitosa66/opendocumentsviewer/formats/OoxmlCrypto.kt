package io.github.guilhermefeitosa66.opendocumentsviewer.formats

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected Office 2007+ files (.docx, .xlsx, .pptx…): an OLE2 container with
 * EncryptionInfo and EncryptedPackage. ECMA-376 "agile" (Office 2010+) and "standard" (Office
 * 2007) AES encryption, with the platform's crypto (no library). The package is decrypted into
 * memory only, never written anywhere.
 */
object OoxmlCrypto {
    class WrongPassword : Exception("wrong password")
    class Unsupported(message: String) : Exception(message)

    /** An OLE2 file holding an encrypted OOXML package. */
    fun isEncryptedPackage(bytes: ByteArray): Boolean =
        Cfb.isCfb(bytes) && runCatching { Cfb.open(bytes).let { it.has("EncryptionInfo") && it.has("EncryptedPackage") } }.getOrDefault(false)

    /** The decrypted zip package; throws [WrongPassword] or [Unsupported]. */
    fun decrypt(bytes: ByteArray, password: String): ByteArray {
        val cfb = Cfb.open(bytes)
        val info = cfb.read("EncryptionInfo") ?: throw Unsupported("no EncryptionInfo")
        val pkg = cfb.read("EncryptedPackage") ?: throw Unsupported("no EncryptedPackage")
        val major = u16(info, 0)
        val minor = u16(info, 2)
        return when {
            major == 4 && minor == 4 -> agile(info, pkg, password)
            (major == 3 || major == 4) && minor == 2 -> standard(info, pkg, password)
            else -> throw Unsupported("encryption $major.$minor")
        }
    }

    private val BLOCK_VERIFIER_INPUT = byteArrayOf(0xfe.toByte(), 0xa7.toByte(), 0xd2.toByte(), 0x76, 0x3b, 0x4b, 0x9e.toByte(), 0x79)
    private val BLOCK_VERIFIER_HASH = byteArrayOf(0xd7.toByte(), 0xaa.toByte(), 0x0f, 0x6d, 0x30, 0x61, 0x34, 0x4e)
    private val BLOCK_KEY = byteArrayOf(0x14, 0x6e, 0x0b, 0xe7.toByte(), 0xab.toByte(), 0xac.toByte(), 0xd0.toByte(), 0xd6.toByte())

    private fun agile(info: ByteArray, pkg: ByteArray, password: String): ByteArray {
        val xml = Jsoup.parse(String(info, 8, info.size - 8, Charsets.UTF_8), "", Parser.xmlParser())
        val keyData = xml.getElementsByTag("keyData").firstOrNull() ?: throw Unsupported("no keyData")
        val ek = xml.getElementsByTag("p:encryptedKey").firstOrNull() ?: xml.getElementsByTag("encryptedKey").firstOrNull()
            ?: throw Unsupported("no password key (certificate encryption?)")
        fun b64(e: org.jsoup.nodes.Element, a: String) = Base64.getDecoder().decode(e.attr(a))
        if (!ek.attr("cipherAlgorithm").equals("AES", true) || !keyData.attr("cipherAlgorithm").equals("AES", true)) throw Unsupported(ek.attr("cipherAlgorithm"))
        val hashName = algorithm(ek.attr("hashAlgorithm"))
        val salt = b64(ek, "saltValue")
        val spin = ek.attr("spinCount").toInt()
        val keyBytes = ek.attr("keyBits").toInt() / 8
        val blockSize = ek.attr("blockSize").toInt()

        var h = digest(hashName, salt, password.toByteArray(Charsets.UTF_16LE))
        val iter = ByteArray(4)
        for (i in 0 until spin) {
            iter[0] = i.toByte(); iter[1] = (i shr 8).toByte(); iter[2] = (i shr 16).toByte(); iter[3] = (i shr 24).toByte()
            h = digest(hashName, iter, h)
        }
        fun derive(block: ByteArray) = fit(digest(hashName, h, block), keyBytes, 0x36)
        val iv = fit(salt, blockSize, 0x36)

        val verifierInput = aesCbc(derive(BLOCK_VERIFIER_INPUT), iv, b64(ek, "encryptedVerifierHashInput"))
            .copyOf(ek.attr("saltSize").toIntOrNull() ?: 16)
        val verifierHash = aesCbc(derive(BLOCK_VERIFIER_HASH), iv, b64(ek, "encryptedVerifierHashValue"))
        val expected = digest(hashName, verifierInput)
        if (!expected.contentEquals(verifierHash.copyOf(expected.size))) throw WrongPassword()
        val secret = aesCbc(derive(BLOCK_KEY), iv, b64(ek, "encryptedKeyValue")).copyOf(keyData.attr("keyBits").toInt() / 8)

        val dataHash = algorithm(keyData.attr("hashAlgorithm"))
        val dataSalt = b64(keyData, "saltValue")
        val dataBlock = keyData.attr("blockSize").toInt()
        val size = u64(pkg, 0)
        val out = java.io.ByteArrayOutputStream(size.toInt().coerceAtLeast(0))
        var offset = 8
        var index = 0
        while (offset < pkg.size && out.size() < size) {
            val end = minOf(pkg.size, offset + 4096)
            var chunk = pkg.copyOfRange(offset, end)
            if (chunk.size % dataBlock != 0) chunk = chunk.copyOf((chunk.size / dataBlock + 1) * dataBlock)
            val idx = byteArrayOf(index.toByte(), (index shr 8).toByte(), (index shr 16).toByte(), (index shr 24).toByte())
            val segIv = fit(digest(dataHash, dataSalt, idx), dataBlock, 0x36)
            out.write(aesCbc(secret, segIv, chunk))
            offset = end
            index++
        }
        return out.toByteArray().copyOf(size.toInt())
    }

    private fun standard(info: ByteArray, pkg: ByteArray, password: String): ByteArray {
        val headerSize = i32(info, 8)
        val h = 12
        val algId = i32(info, h + 8)
        val keyBits = i32(info, h + 16).let { if (it == 0) 128 else it }
        if (algId != 0x660E && algId != 0x660F && algId != 0x6610) throw Unsupported("algorithm 0x${algId.toString(16)}")
        val v = h + headerSize
        val saltSize = i32(info, v)
        val salt = info.copyOfRange(v + 4, v + 4 + saltSize)
        val encVerifier = info.copyOfRange(v + 4 + saltSize, v + 4 + saltSize + 16)
        val hashSize = i32(info, v + 20 + saltSize)
        val encVerifierHash = info.copyOfRange(v + 24 + saltSize, minOf(info.size, v + 24 + saltSize + 32))

        var hash = digest("SHA-1", salt, password.toByteArray(Charsets.UTF_16LE))
        val iter = ByteArray(4)
        for (i in 0 until 50000) {
            iter[0] = i.toByte(); iter[1] = (i shr 8).toByte(); iter[2] = (i shr 16).toByte(); iter[3] = (i shr 24).toByte()
            hash = digest("SHA-1", iter, hash)
        }
        val final = digest("SHA-1", hash, ByteArray(4))
        val buf1 = ByteArray(64) { 0x36 }; val buf2 = ByteArray(64) { 0x5c }
        for (i in final.indices) { buf1[i] = (buf1[i].toInt() xor final[i].toInt()).toByte(); buf2[i] = (buf2[i].toInt() xor final[i].toInt()).toByte() }
        val key = (digest("SHA-1", buf1) + digest("SHA-1", buf2)).copyOf(keyBits / 8)

        val verifier = aesEcb(key, encVerifier)
        val verifierHash = aesEcb(key, encVerifierHash).copyOf(hashSize)
        if (!digest("SHA-1", verifier).contentEquals(verifierHash)) throw WrongPassword()
        val size = u64(pkg, 0)
        var body = pkg.copyOfRange(8, pkg.size)
        if (body.size % 16 != 0) body = body.copyOf((body.size / 16 + 1) * 16)
        return aesEcb(key, body).copyOf(size.toInt())
    }

    private fun algorithm(name: String) = when (name.uppercase().replace("-", "")) {
        "SHA1" -> "SHA-1"; "SHA256" -> "SHA-256"; "SHA384" -> "SHA-384"; "SHA512" -> "SHA-512"; "MD5" -> "MD5"
        else -> throw Unsupported("hash $name")
    }

    private fun digest(alg: String, vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance(alg)
        for (p in parts) md.update(p)
        return md.digest()
    }

    private fun fit(b: ByteArray, size: Int, pad: Int): ByteArray =
        if (b.size >= size) b.copyOf(size) else b + ByteArray(size - b.size) { pad.toByte() }

    private fun aesCbc(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(if (data.size % 16 == 0) data else data.copyOf((data.size / 16 + 1) * 16))
    }

    private fun aesEcb(key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    private fun u16(d: ByteArray, o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
    private fun i32(d: ByteArray, o: Int) = u16(d, o) or (u16(d, o + 2) shl 16)
    private fun u64(d: ByteArray, o: Int): Long = (i32(d, o).toLong() and 0xFFFFFFFFL) or (i32(d, o + 4).toLong() shl 32)
}
