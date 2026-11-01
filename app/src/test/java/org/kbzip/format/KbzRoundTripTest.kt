package org.kbzip.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import org.junit.Test

class KbzRoundTripTest {

    private val small = "hello, Karen".toByteArray()
    private val big = ByteArray(10_000_000) { ((it * 31) % 251).toByte() } // spans 3 blocks
    private val random = ByteArray(300_000).also { java.util.Random(1).nextBytes(it) } // incompressible -> stored

    private fun build(): File {
        val f = File.createTempFile("kbz", ".kbz")
        f.deleteOnExit()
        FileOutputStream(f).use { os ->
            KbzWriter(os, "Made for Karen Benny").use { w ->
                w.addDirectory("docs", 1)
                w.addFile("docs/a.txt", 1, ByteArrayInputStream(small))
                w.addFile("big.bin", 2, ByteArrayInputStream(big))
                w.addFile("empty", 3, ByteArrayInputStream(ByteArray(0)))
                w.addFile("docs/b.txt", 4, ByteArrayInputStream(small))
                w.addFile("random.bin", 5, ByteArrayInputStream(random))
                w.addDirectory("emptydir", 6)
            }
        }
        return f
    }

    private fun bytesOf(r: KbzReader, path: String): ByteArray {
        val e = r.entries.first { it.path == path }
        return ByteArrayOutputStream().also { r.extract(e, it) }.toByteArray()
    }

    @Test
    fun roundTrip() {
        val f = build()
        RandomAccessFile(f, "r").use { raf ->
            val r = KbzReader(raf.channel)
            assertEquals("Made for Karen Benny", r.dedication)
            assertEquals(7, r.entries.size)
            assertTrue(r.entries.first { it.path == "emptydir" }.isDir)
            assertArrayEquals(small, bytesOf(r, "docs/a.txt"))
            assertArrayEquals(small, bytesOf(r, "docs/b.txt"))
            assertArrayEquals(big, bytesOf(r, "big.bin"))
            assertArrayEquals(random, bytesOf(r, "random.bin"))
            assertEquals(0, bytesOf(r, "empty").size)
        }
    }

    @Test
    fun detectsCorruption() {
        val f = build()
        val data = f.readBytes()
        data[100] = (data[100].toInt() xor 0xFF).toByte() // inside the first block
        f.writeBytes(data)
        RandomAccessFile(f, "r").use { raf ->
            val r = KbzReader(raf.channel)
            assertThrows(KbzException::class.java) { bytesOf(r, "docs/a.txt") }
        }
    }

    @Test
    fun rejectsTruncatedArchive() {
        val f = build()
        val data = f.readBytes()
        f.writeBytes(data.copyOf(data.size - 10))
        RandomAccessFile(f, "r").use { raf ->
            assertThrows(KbzException::class.java) { KbzReader(raf.channel) }
        }
    }

    @Test
    fun rejectsUnsafePathsAndDuplicates() {
        for (bad in listOf("../x", "/abs", "a//b", "a/./b", "a\\b", "")) {
            assertFalse(bad, Kbz.isSafePath(bad))
        }
        val w = KbzWriter(ByteArrayOutputStream())
        w.addFile("x", 0, ByteArrayInputStream(small))
        assertThrows(KbzException::class.java) { w.addFile("x", 0, ByteArrayInputStream(small)) }
        assertThrows(KbzException::class.java) { w.addFile("../evil", 0, ByteArrayInputStream(small)) }
    }
}
