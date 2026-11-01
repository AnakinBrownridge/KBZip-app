package org.kbzip.format

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Constants and helpers shared by the reader and writer. See SPEC.md. */
object Kbz {
    val MAGIC = byteArrayOf('K'.code.toByte(), 'B'.code.toByte(), 'Z'.code.toByte(), '1'.code.toByte())
    val FOOTER_MAGIC = byteArrayOf('1'.code.toByte(), 'Z'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    const val VERSION = 1
    const val CODEC_STORE = 0
    const val CODEC_DEFLATE = 1
    const val BLOCK_SIZE = 4 * 1024 * 1024
    const val MAX_BLOCK = 64 * 1024 * 1024
    const val MAX_INDEX = 1 shl 30
    const val HEADER_FIXED = 8
    const val FOOTER_SIZE = 24
    const val BLOCK_HEADER_SIZE = 13

    fun isSafePath(p: String): Boolean {
        if (p.isEmpty() || p.startsWith("/") || p.endsWith("/")) return false
        if (p.indexOf('\\') >= 0 || p.indexOf('\u0000') >= 0) return false
        return p.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    internal fun writeVarint(out: OutputStream, value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    internal fun deflate(src: ByteArray, len: Int, level: Int): ByteArray {
        val d = Deflater(level, true) // true = raw DEFLATE, no zlib wrapper
        try {
            d.setInput(src, 0, len)
            d.finish()
            val bos = ByteArrayOutputStream(len / 2 + 64)
            val buf = ByteArray(64 * 1024)
            while (!d.finished()) {
                val n = d.deflate(buf)
                bos.write(buf, 0, n)
            }
            return bos.toByteArray()
        } finally {
            d.end()
        }
    }

    internal fun inflate(src: ByteArray, rawLen: Int): ByteArray {
        val inf = Inflater(true)
        try {
            inf.setInput(src)
            val out = ByteArray(rawLen)
            var off = 0
            while (off < rawLen) {
                val n = inf.inflate(out, off, rawLen - off)
                if (n == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break
                off += n
            }
            if (off != rawLen) throw KbzException("Compressed data is shorter than declared")
            return out
        } catch (e: DataFormatException) {
            throw KbzException("Corrupt compressed data", e)
        } finally {
            inf.end()
        }
    }
}

class KbzException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * One item in an archive.
 * [mtime] is seconds since the Unix epoch. [crc32] is an unsigned 32-bit value held in a Long.
 */
data class KbzEntry(
    val path: String,
    val isDir: Boolean,
    val mtime: Long,
    val size: Long,
    val startBlock: Int,
    val offset: Long,
    val crc32: Long
) {
    val name: String get() = path.substringAfterLast('/')
    val parent: String get() = path.substringBeforeLast('/', "")
}
