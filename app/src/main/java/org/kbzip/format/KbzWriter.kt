package org.kbzip.format

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Streaming KBZip writer. Needs only sequential output.
 * Call [finish] (or [close]) to write the index and footer; without it the archive is unreadable.
 */
class KbzWriter(
    private val sink: OutputStream,
    dedication: String = "",
    private val level: Int = Deflater.DEFAULT_COMPRESSION
) : Closeable {

    private var position = 0L
    private val block = ByteArray(Kbz.BLOCK_SIZE)
    private var blockLen = 0
    private var blockOffsets = LongArray(64)
    private var blockSizes = LongArray(64)
    private var blockCount = 0
    private val entries = ArrayList<KbzEntry>()
    private val seen = HashSet<String>()
    private var finished = false

    init {
        val ded = dedication.toByteArray(Charsets.UTF_8)
        require(ded.size <= 0xFFFF) { "Dedication too long (max 65535 bytes)" }
        val h = ByteBuffer.allocate(Kbz.HEADER_FIXED).order(ByteOrder.LITTLE_ENDIAN)
        h.put(Kbz.MAGIC).put(Kbz.VERSION.toByte()).put(0.toByte()).putShort(ded.size.toShort())
        emit(h.array(), 0, h.capacity())
        emit(ded, 0, ded.size)
    }

    fun addDirectory(path: String, mtime: Long) {
        register(path)
        entries.add(KbzEntry(path, true, mtime, 0, 0, 0, 0))
    }

    fun addFile(path: String, mtime: Long, input: InputStream) {
        register(path)
        val startBlock = blockCount
        val offset = blockLen.toLong()
        val crc = CRC32()
        var size = 0L
        while (true) {
            val n = input.read(block, blockLen, block.size - blockLen)
            if (n < 0) break
            if (n == 0) continue
            crc.update(block, blockLen, n)
            blockLen += n
            size += n
            if (blockLen == block.size) flushBlock()
        }
        entries.add(KbzEntry(path, false, mtime, size, startBlock, offset, crc.value))
    }

    private fun register(path: String) {
        check(!finished) { "Archive already finished" }
        if (!Kbz.isSafePath(path)) throw KbzException("Unsafe or invalid path: $path")
        if (!seen.add(path)) throw KbzException("Duplicate path: $path")
    }

    private fun emit(b: ByteArray, off: Int, len: Int) {
        sink.write(b, off, len)
        position += len
    }

    private fun flushBlock() {
        if (blockLen == 0) return
        val crc = CRC32().apply { update(block, 0, blockLen) }
        val packed = Kbz.deflate(block, blockLen, level)
        val useDeflate = packed.size < blockLen
        val payload = if (useDeflate) packed else block
        val payloadLen = if (useDeflate) packed.size else blockLen

        if (blockCount == blockOffsets.size) {
            blockOffsets = blockOffsets.copyOf(blockCount * 2)
            blockSizes = blockSizes.copyOf(blockCount * 2)
        }
        blockOffsets[blockCount] = position
        blockSizes[blockCount] = blockLen.toLong()
        blockCount++

        val h = ByteBuffer.allocate(Kbz.BLOCK_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(blockLen).putInt(payloadLen)
            .put((if (useDeflate) Kbz.CODEC_DEFLATE else Kbz.CODEC_STORE).toByte())
            .putInt(crc.value.toInt())
        emit(h.array(), 0, h.capacity())
        emit(payload, 0, payloadLen)
        blockLen = 0
    }

    private fun buildIndex(): ByteArray {
        val o = ByteArrayOutputStream(entries.size * 48 + blockCount * 8 + 16)
        Kbz.writeVarint(o, blockCount.toLong())
        for (i in 0 until blockCount) {
            Kbz.writeVarint(o, blockOffsets[i])
            Kbz.writeVarint(o, blockSizes[i])
        }
        Kbz.writeVarint(o, entries.size.toLong())
        for (e in entries) {
            o.write(if (e.isDir) 1 else 0)
            val p = e.path.toByteArray(Charsets.UTF_8)
            Kbz.writeVarint(o, p.size.toLong())
            o.write(p)
            Kbz.writeVarint(o, e.mtime.coerceAtLeast(0))
            if (!e.isDir) {
                Kbz.writeVarint(o, e.size)
                Kbz.writeVarint(o, e.startBlock.toLong())
                Kbz.writeVarint(o, e.offset)
                val c = e.crc32.toInt()
                o.write(c); o.write(c ushr 8); o.write(c ushr 16); o.write(c ushr 24)
            }
        }
        return o.toByteArray()
    }

    fun finish() {
        if (finished) return
        flushBlock()
        val index = buildIndex()
        if (index.size > Kbz.MAX_INDEX) throw KbzException("Index too large")
        val crc = CRC32().apply { update(index) }
        val packed = Kbz.deflate(index, index.size, 6)
        val indexOffset = position
        emit(packed, 0, packed.size)
        val f = ByteBuffer.allocate(Kbz.FOOTER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        f.putLong(indexOffset).putInt(packed.size).putInt(index.size).putInt(crc.value.toInt()).put(Kbz.FOOTER_MAGIC)
        emit(f.array(), 0, f.capacity())
        sink.flush()
        finished = true
    }

    override fun close() {
        try {
            finish()
        } finally {
            sink.close()
        }
    }
}
