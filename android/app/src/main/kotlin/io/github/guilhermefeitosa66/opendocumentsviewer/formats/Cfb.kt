package io.github.guilhermefeitosa66.opendocumentsviewer.formats

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compound File Binary (OLE2), the container of Word, Excel and PowerPoint 97–2003 files (and of
 * encrypted OOXML). Read-only, from bytes in memory: streams by path ("WordDocument",
 * "ObjectPool/_123/Ole"). No library.
 */
class Cfb private constructor(private val data: ByteArray) {
    class NotCfb(message: String) : Exception(message)

    private val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    private val sectorSize = 1 shl u16(0x1E)
    private val miniSectorSize = 1 shl u16(0x20)
    private val miniCutoff = u32(0x38)
    private val fat: IntArray
    private val miniFat: IntArray
    private val miniStream: ByteArray
    private val entries = ArrayList<Entry>()

    /** Stream path → (start sector, size). */
    val streams = LinkedHashMap<String, Entry>()

    class Entry(val name: String, val type: Int, val left: Int, val right: Int, val child: Int, val start: Int, val size: Long)

    init {
        // FAT sectors: the first 109 in the header, the rest through the DIFAT chain.
        val fatSectors = ArrayList<Int>()
        val numFat = u32(0x2C)
        for (i in 0 until minOf(109, numFat)) fatSectors += u32(0x4C + i * 4)
        var difat = u32(0x44)
        var guard = 0
        while (fatSectors.size < numFat && difat >= 0 && guard++ < 100_000) {
            val off = offset(difat)
            val per = sectorSize / 4 - 1
            for (i in 0 until per) { if (fatSectors.size < numFat) fatSectors += u32(off + i * 4) }
            difat = u32(off + per * 4)
        }
        val perSector = sectorSize / 4
        fat = IntArray(fatSectors.size * perSector)
        fatSectors.forEachIndexed { i, s ->
            val off = offset(s)
            for (j in 0 until perSector) fat[i * perSector + j] = if (off + j * 4 + 4 <= data.size) u32(off + j * 4) else END
        }
        // Directory.
        val dir = chain(u32(0x30))
        for (k in 0 until dir.size / 128) {
            val o = k * 128
            val bb = ByteBuffer.wrap(dir, o, 128).order(ByteOrder.LITTLE_ENDIAN)
            val nameLen = (bb.getShort(o + 0x40).toInt() and 0xFFFF).coerceIn(0, 64)
            val name = String(dir, o, maxOf(0, nameLen - 2), Charsets.UTF_16LE)
            entries += Entry(
                name, dir[o + 0x42].toInt(), bb.getInt(o + 0x44), bb.getInt(o + 0x48), bb.getInt(o + 0x4C),
                bb.getInt(o + 0x74), bb.getInt(o + 0x78).toLong() and 0xFFFFFFFFL,
            )
        }
        val root = entries.firstOrNull() ?: throw NotCfb("no directory")
        miniStream = chain(root.start).copyOf(root.size.toInt().coerceAtMost(Int.MAX_VALUE - 8).coerceAtMost(data.size))
        val mf = chain(u32(0x3C).takeIf { u32(0x40) > 0 } ?: END)
        miniFat = IntArray(mf.size / 4) { ByteBuffer.wrap(mf).order(ByteOrder.LITTLE_ENDIAN).getInt(it * 4) }
        walk(root.child, "", HashSet())
    }

    private fun walk(id: Int, prefix: String, seen: MutableSet<Int>) {
        if (id < 0 || id >= entries.size || !seen.add(id)) return
        val e = entries[id]
        walk(e.left, prefix, seen)
        val path = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
        when (e.type) {
            2 -> streams[path] = e
            1 -> walk(e.child, path, seen)
        }
        walk(e.right, prefix, seen)
    }

    fun has(path: String) = streams.containsKey(path)

    fun read(path: String): ByteArray? {
        val e = streams[path] ?: return null
        val size = e.size.coerceAtMost(data.size.toLong() * 2).toInt()
        return if (e.size < miniCutoff) miniChain(e.start, size) else chain(e.start).copyOf(minOf(size, chain(e.start).size))
    }

    private fun chain(start: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && s < fat.size && guard++ < fat.size + 1) {
            val off = offset(s)
            if (off >= data.size) break
            out.write(data, off, minOf(sectorSize, data.size - off))
            s = fat[s]
        }
        return out.toByteArray()
    }

    private fun miniChain(start: Int, size: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(size)
        var s = start
        var guard = 0
        while (s >= 0 && s < miniFat.size && out.size() < size && guard++ < miniFat.size + 1) {
            val off = s * miniSectorSize
            if (off >= miniStream.size) break
            out.write(miniStream, off, minOf(miniSectorSize, miniStream.size - off))
            s = miniFat[s]
        }
        return out.toByteArray().copyOf(minOf(size, out.size()))
    }

    private fun offset(sector: Int) = (sector + 1) * sectorSize
    private fun u16(o: Int) = buf.getShort(o).toInt() and 0xFFFF
    private fun u32(o: Int) = buf.getInt(o)

    companion object {
        private const val END = -2
        private val SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

        fun isCfb(bytes: ByteArray) = bytes.size >= 512 && (0 until 8).all { bytes[it] == SIGNATURE[it] }

        fun open(bytes: ByteArray): Cfb {
            if (!isCfb(bytes)) throw NotCfb("not an OLE2 compound file")
            return try { Cfb(bytes) } catch (e: NotCfb) { throw e } catch (e: Exception) { throw NotCfb("damaged compound file: ${e.message}") }
        }
    }
}
