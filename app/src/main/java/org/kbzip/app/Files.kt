package org.kbzip.app

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import org.kbzip.format.KbzReader
import org.kbzip.format.KbzWriter
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream

/** A .kbz file discovered while scanning a folder. */
data class Found(val name: String, val uri: Uri, val size: Long)

/** Something to put in a new archive. [uri] == null means a directory entry. */
data class Source(val path: String, val uri: Uri?, val mtime: Long)

private data class Child(val id: String, val name: String, val isDir: Boolean, val size: Long, val mtimeMs: Long)

/**
 * An open archive. Closing it closes the file descriptor, which also ends the reader's channel.
 * (We deliberately close only the descriptor, so the fd is never closed twice.)
 */
class OpenArchive(private val pfd: ParcelFileDescriptor, val reader: KbzReader, val name: String) : Closeable {
    override fun close() {
        try { pfd.close() } catch (_: IOException) {}
    }
}

private fun listChildren(ctx: Context, tree: Uri, parentId: String): List<Child> {
    val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
    val cols = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
    )
    val out = ArrayList<Child>()
    ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val name = c.getString(1) ?: continue
            out.add(
                Child(
                    id = c.getString(0),
                    name = name,
                    isDir = c.getString(2) == Document.MIME_TYPE_DIR,
                    size = if (c.isNull(3)) -1 else c.getLong(3),
                    mtimeMs = if (c.isNull(4)) 0 else c.getLong(4)
                )
            )
        }
    }
    return out
}

private fun cleanName(n: String) = n.replace('/', '_').replace('\\', '_').replace('\u0000', '_')

fun displayName(ctx: Context, uri: Uri): String {
    try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
    } catch (_: Exception) {}
    return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
}

fun lastModifiedSeconds(ctx: Context, uri: Uri): Long {
    try {
        ctx.contentResolver.query(uri, arrayOf(Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) / 1000
        }
    } catch (_: Exception) {}
    return System.currentTimeMillis() / 1000
}

/** Recursively scans a granted folder for *.kbz files. */
fun scanForKbz(ctx: Context, tree: Uri): List<Found> {
    val found = ArrayList<Found>()
    val stack = ArrayDeque<String>()
    stack.addLast(DocumentsContract.getTreeDocumentId(tree))
    while (stack.isNotEmpty()) {
        for (ch in listChildren(ctx, tree, stack.removeLast())) {
            if (ch.isDir) stack.addLast(ch.id)
            else if (ch.name.endsWith(".kbz", ignoreCase = true))
                found.add(Found(ch.name, DocumentsContract.buildDocumentUriUsingTree(tree, ch.id), ch.size))
        }
    }
    return found.sortedBy { it.name.lowercase() }
}

/** Turns a picked folder into archive sources (the folder itself becomes the top-level directory). */
fun collectFolder(ctx: Context, tree: Uri): List<Source> {
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    val rootName = cleanName(displayName(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, rootId)))
    val out = ArrayList<Source>()
    out.add(Source(rootName, null, System.currentTimeMillis() / 1000))
    val stack = ArrayDeque<Pair<String, String>>()
    stack.addLast(rootId to rootName)
    while (stack.isNotEmpty()) {
        val (id, rel) = stack.removeLast()
        for (ch in listChildren(ctx, tree, id)) {
            val path = rel + "/" + cleanName(ch.name)
            val secs = ch.mtimeMs / 1000
            if (ch.isDir) {
                out.add(Source(path, null, secs))
                stack.addLast(ch.id to path)
            } else {
                out.add(Source(path, DocumentsContract.buildDocumentUriUsingTree(tree, ch.id), secs))
            }
        }
    }
    return out
}

fun collectFiles(ctx: Context, uris: List<Uri>): List<Source> =
    uris.map { Source(cleanName(displayName(ctx, it)), it, lastModifiedSeconds(ctx, it)) }

fun createArchive(ctx: Context, dest: Uri, sources: List<Source>, dedication: String, progress: (String) -> Unit) {
    val os = ctx.contentResolver.openOutputStream(dest, "w") ?: throw IOException("Cannot open destination")
    KbzWriter(os.buffered(256 * 1024), dedication).use { w ->
        sources.forEachIndexed { i, s ->
            if (s.uri == null) {
                w.addDirectory(s.path, s.mtime)
            } else {
                val input = ctx.contentResolver.openInputStream(s.uri) ?: throw IOException("Cannot read ${s.path}")
                input.use { w.addFile(s.path, s.mtime, it) }
            }
            if (i % 25 == 0) progress("Packing ${i + 1} of ${sources.size}…")
        }
    }
}

fun extractOne(ctx: Context, archive: OpenArchive, entryPath: String, dest: Uri) {
    val entry = archive.reader.entries.first { it.path == entryPath }
    val os: OutputStream = ctx.contentResolver.openOutputStream(dest, "w") ?: throw IOException("Cannot open destination")
    os.buffered(256 * 1024).use { archive.reader.extract(entry, it) }
}

fun extractAll(ctx: Context, archive: OpenArchive, destTree: Uri, progress: (String) -> Unit): Int {
    val root = DocumentFile.fromTreeUri(ctx, destTree) ?: throw IOException("Cannot open destination folder")
    val dirs = HashMap<String, DocumentFile>()
    dirs[""] = root

    fun ensureDir(path: String): DocumentFile {
        dirs[path]?.let { return it }
        val parent = ensureDir(path.substringBeforeLast('/', ""))
        val made = parent.createDirectory(path.substringAfterLast('/'))
            ?: throw IOException("Cannot create folder $path")
        dirs[path] = made
        return made
    }

    var files = 0
    val entries = archive.reader.entries
    entries.forEachIndexed { i, e ->
        if (e.isDir) {
            ensureDir(e.path)
        } else {
            val parent = ensureDir(e.parent)
            val doc = parent.createFile("application/octet-stream", e.name)
                ?: throw IOException("Cannot create ${e.path}")
            val os = ctx.contentResolver.openOutputStream(doc.uri, "w") ?: throw IOException("Cannot write ${e.path}")
            os.buffered(256 * 1024).use { archive.reader.extract(e, it) }
            files++
        }
        if (i % 25 == 0) progress("Extracting ${i + 1} of ${entries.size}…")
    }
    return files
}

fun formatSize(n: Long): String {
    if (n < 0) return "?"
    if (n < 1024) return "$n B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = n.toDouble()
    var u = -1
    while (v >= 1024 && u < units.lastIndex) { v /= 1024; u++ }
    return String.format("%.1f %s", v, units[u])
}
