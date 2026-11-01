package org.kbzip.format

import java.io.Closeable
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.CRC32

/**
 * Random-access KBZip reader. Opening reads only the header, footer and index.
 * File data is read and verified block by block on demand.
 * Not thread-safe.
 */
class KbzReader(private val ch: FileChannel) : Closeable {

    val dedication: String
    val entries: List<KbzEntry>

    private val blockOffsets: LongArray
    private val blockSizes: LongArray
    private val fileSize: Long = ch.size()

    private var cachedIndex = -1
    private var cachedData: ByteArray? = null

    init {
        if (fileSize < Kbz.HEADER_FIXED + Kbz.FOOTER_SIZE) throw KbzException("Not a KBZip file (too small)")

        // Header
        val h = ByteBuffer.wrap(readAt(0, Kbz.HEADER_FIXED)).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { h.get(it) }
        if (!magic.contentEquals(Kbz.MAGIC)) throw KbzException("Not a KBZip file (bad magic)")
        val version = h.get().toInt() and 0xFF
        if (version != Kbz.VERSION) throw KbzException("Unsupported KBZip version $version")
        val flags = h.get().toInt() and 0xFF
        if (flags != 0) throw KbzException("Unsupported flags")
        val dedLen = h.short.toInt() and 0xFFFF
        if (Kbz.HEADER_FIXED + dedLen + Kbz.FOOTER_SIZE > fileSize) throw KbzException("Corrupt header")
        dedication = String(readAt(Kbz.HEADER_FIXED.toLong(), dedLen), Charsets.UTF_8)
        val dataStart = Kbz.HEADER_FIXED + dedLen

        // Footer
        val f = ByteBuffer.wrap(readAt(fileSize - Kbz.FOOTER_SIZE, Kbz.FOOTER_SIZE)).order(ByteOrder.LITTLE_ENDIAN)
        val indexOffset = f.long
        val packedLen = f.int.toLong() and 0xFFFFFFFFL
        val rawLen = f.int.toLong() and 0xFFFFFFFFL
        val indexCrc = f.int.toLong() and 0xFFFFFFFFL
        val fmagic = ByteArray(4).also { f.get(it) }
        if (!fmagic.contentEquals(Kbz.FOOTER_MAGIC)) throw KbzException("Missing footer (incomplete archive?)")
        if (indexOffset < dataStart || indexOffset + packedLen != fileSize - Kbz.FOOTER_SIZE)
            throw KbzException("Corrupt footer")
        if (rawLen > Kbz.MAX_INDEX || packedLen > Kbz.MAX_INDEX) throw KbzException("Index too large")

        // Index
        val raw = Kbz.inflate(readAt(indexOffset, packedLen.toInt()), rawLen.toInt())
        val crc = CRC32().apply { update(raw) }
        if (crc.value != indexCrc) throw KbzException("Index checksum mismatch")

        val c = Cursor(raw)
        val blockCount = c.varint()
        if (blockCount > raw.size) throw KbzException("Corrupt index")
        blockOffsets = LongArray(blockCount.toInt())
        blockSizes = LongArray(blockCount.toInt())
        for (i in blockOffsets.indices) {
            blockOffsets[i] = c.varint()
            blockSizes[i] = c.varint()
            if (blockOffsets[i] < dataStart || blockOffsets[i] >= indexOffset ||
                blockSizes[i] < 1 || blockSizes[i] > Kbz.MAX_BLOCK
            ) throw KbzException("Corrupt block table")
        }
        val entryCount = c.varint()
        if (entryCount > raw.size) throw KbzException("Corrupt index")
        val list = ArrayList<KbzEntry>(entryCount.toInt())
        repeat(entryCount.toInt()) {
            val type = c.byte()
            if (type > 1) throw KbzException("Unknown entry type $type")
            val path = c.string()
            if (!Kbz.isSafePath(path)) throw KbzException("Unsafe path in archive: $path")
            val mtime = c.varint()
            if (type == 1) {
                list.add(KbzEntry(path, true, mtime, 0, 0, 0, 0))
            } else {
                val size = c.varint()
                val start = c.varint()
                val off = c.varint()
                val crc32 = c.u32()
                if (size > 0 && (start >= blockCount || off >= blockSizes[start.toInt()]))
                    throw KbzException("Corrupt entry: $path")
                list.add(KbzEntry(path, false, mtime, size, start.toInt(), off, crc32))
            }
        }
        entries = list
    }

    /** Streams one file's bytes into [out], verifying every checksum. */
    fun extract(entry: KbzEntry, out: OutputStream) {
        require(!entry.isDir) { "Not a file: ${entry.path}" }
        val crc = CRC32()
        var remaining = entry.size
        var b = entry.startBlock
        var off = entry.offset
        while (remaining > 0) {
            if (b >= blockOffsets.size) throw KbzException("File runs past the last block: ${entry.path}")
            val data = readBlock(b)
            val take = minOf(remaining, data.size - off).toInt()
            if (take <= 0) throw KbzException("Corrupt entry: ${entry.path}")
            out.write(data, off.toInt(), take)
            crc.update(data, off.toInt(), take)
            remaining -= take
            off = 0
            b++
        }
        if (crc.value != entry.crc32) throw KbzException("Checksum mismatch in ${entry.path}")
    }

    private fun readBlock(i: Int): ByteArray {
        if (i == cachedIndex) return cachedData!!
        val h = ByteBuffer.wrap(readAt(blockOffsets[i], Kbz.BLOCK_HEADER_SIZE)).order(ByteOrder.LITTLE_ENDIAN)
        val rawLen = h.int.toLong() and 0xFFFFFFFFL
        val packedLen = h.int.toLong() and 0xFFFFFFFFL
        val codec = h.get().toInt() and 0xFF
        val crc = h.int.toLong() and 0xFFFFFFFFL
        if (rawLen != blockSizes[i] || packedLen > Kbz.MAX_BLOCK + 1024L ||
            blockOffsets[i] + Kbz.BLOCK_HEADER_SIZE + packedLen > fileSize
        ) throw KbzException("Corrupt block header (block $i)")
        val payload = readAt(blockOffsets[i] + Kbz.BLOCK_HEADER_SIZE, packedLen.toInt())
        val data = when (codec) {
            Kbz.CODEC_STORE -> {
                if (packedLen != rawLen) throw KbzException("Corrupt stored block $i")
                payload
            }
            Kbz.CODEC_DEFLATE -> Kbz.inflate(payload, rawLen.toInt())
            else -> throw KbzException("Unsupported codec $codec in block $i")
        }
        val actual = CRC32().apply { update(data) }
        if (actual.value != crc) throw KbzException("Checksum mismatch in block $i")
        cachedIndex = i
        cachedData = data
        return data
    }

    private fun readAt(pos: Long, n: Int): ByteArray {
        val buf = ByteBuffer.allocate(n)
        var p = pos
        while (buf.hasRemaining()) {
            val r = ch.read(buf, p)
            if (r < 0) throw KbzException("Unexpected end of file")
            p += r
        }
        return buf.array()
    }

    override fun close() {
        ch.close()
    }

    private class Cursor(val b: ByteArray) {
        var p = 0
        fun byte(): Int {
            if (p >= b.size) throw KbzException("Index truncated")
            return b[p++].toInt() and 0xFF
        }
        fun varint(): Long {
            var shift = 0
            var r = 0L
            while (true) {
                val x = byte()
                r = r or ((x and 0x7F).toLong() shl shift)
                if ((x and 0x80) == 0) {
                    if (r < 0) throw KbzException("Bad varint")
                    return r
                }
                shift += 7
                if (shift > 63) throw KbzException("Bad varint")
            }
        }
        fun u32(): Long {
            var r = 0L
            for (i in 0 until 4) r = r or (byte().toLong() shl (8 * i))
            return r
        }
        fun string(): String {
            val len = varint()
            if (len > b.size - p) throw KbzException("Index truncated")
            val s = String(b, p, len.toInt(), Charsets.UTF_8)
            p += len.toInt()
            return s
        }
    }
}
