package org.kbzip.app

import android.net.Uri
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kbzip.format.KbzEntry
import java.io.FileInputStream
import java.io.IOException

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incoming.value = intent?.data
        setContent { MaterialTheme { KbzApp(incoming) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming.value = intent.data
    }
}

private enum class Screen { HOME, CREATE, VIEW }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KbzApp(incoming: MutableState<Uri?>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(Screen.HOME) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var archive by remember { mutableStateOf<OpenArchive?>(null) }
    var found by remember { mutableStateOf<List<Found>>(emptyList()) }
    var sources by remember { mutableStateOf<List<Source>>(emptyList()) }
    var dedication by remember { mutableStateOf("Made for Karen Benny") }
    var pendingEntry by remember { mutableStateOf<KbzEntry?>(null) }
    var treeFor by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }

    DisposableEffect(Unit) { onDispose { archive?.close() } }

    /** Runs [job] off the main thread, then [done] on the main thread. */
    fun <T> work(label: String, job: () -> T, done: (T) -> Unit = {}) {
        scope.launch {
            busy = true
            status = label
            try {
                val result = withContext(Dispatchers.IO) { job() }
                done(result)
            } catch (e: Exception) {
                status = "Error: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    fun openArchive(uri: Uri) = work("Opening…", {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open file")
        try {
            OpenArchive(pfd, org.kbzip.format.KbzReader(FileInputStream(pfd.fileDescriptor).channel), displayName(ctx, uri))
        } catch (e: Exception) {
            pfd.close()
            throw e
        }
    }) { opened ->
        archive?.close()
        archive = opened
        filter = ""
        status = ""
        screen = Screen.VIEW
    }

    LaunchedEffect(incoming.value) {
        incoming.value?.let { openArchive(it); incoming.value = null }
    }

    // ---- Activity-result launchers ----
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openArchive(uri)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) work("Reading selection…", { collectFiles(ctx, uris) }) {
            sources = sources + it
            status = "${sources.count { s -> s.uri != null }} file(s) selected"
        }
    }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) when (treeFor) {
            "scan" -> work("Scanning for KBZip files…", { scanForKbz(ctx, tree) }) {
                found = it
                status = "${it.size} KBZip file(s) found"
            }
            "add" -> work("Reading folder…", { collectFolder(ctx, tree) }) {
                sources = sources + it
                status = "${sources.count { s -> s.uri != null }} file(s) selected"
            }
            "extract" -> archive?.let { a ->
                work("Extracting…", { extractAll(ctx, a, tree) { msg -> status = msg } }) {
                    status = "Extracted $it file(s)."
                }
            }
        }
    }
    val saveNew = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { dest ->
        if (dest != null) {
            val snapshot = sources
            work("Packing…", { createArchive(ctx, dest, snapshot, dedication) { msg -> status = msg } }) {
                status = "Created archive with ${snapshot.size} item(s)."
                sources = emptyList()
            }
        }
    }
    val saveEntry = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { dest ->
        val a = archive
        val e = pendingEntry
        if (dest != null && a != null && e != null) {
            work("Extracting…", { extractOne(ctx, a, e.path, dest) }) { status = "Saved ${e.name}." }
        }
    }

    BackHandler(screen != Screen.HOME) { screen = Screen.HOME; status = "" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KBZip") },
                navigationIcon = {
                    if (screen != Screen.HOME) TextButton(onClick = { screen = Screen.HOME; status = "" }) { Text("Back") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)

            when (screen) {
                Screen.HOME -> {
                    Text("Open, create and extract .kbz archives.", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { openDoc.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open a KBZip file")
                    }
                    Button(onClick = { treeFor = "scan"; pickTree.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Find KBZip files in a folder")
                    }
                    Button(onClick = { screen = Screen.CREATE; status = "" }, modifier = Modifier.fillMaxWidth()) {
                        Text("Create an archive")
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(found) { f ->
                            Column(Modifier.fillMaxWidth().clickable { openArchive(f.uri) }.padding(vertical = 10.dp)) {
                                Text(f.name, style = MaterialTheme.typography.titleSmall)
                                Text(formatSize(f.size), style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                    }
                }

                Screen.CREATE -> {
                    OutlinedTextField(
                        value = dedication, onValueChange = { dedication = it.take(200) },
                        label = { Text("Dedication (optional)") }, modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) { Text("Add files") }
                        OutlinedButton(onClick = { treeFor = "add"; pickTree.launch(null) }) { Text("Add folder") }
                    }
                    val fileCount = sources.count { it.uri != null }
                    val dirCount = sources.size - fileCount
                    Text("$fileCount file(s), $dirCount folder(s) selected")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = sources.isNotEmpty() && !busy, onClick = { saveNew.launch("archive.kbz") }) {
                            Text("Create archive…")
                        }
                        TextButton(onClick = { sources = emptyList(); status = "" }) { Text("Clear") }
                    }
                }

                Screen.VIEW -> {
                    val a = archive
                    if (a == null) {
                        Text("No archive open.")
                    } else {
                        Text(a.name, style = MaterialTheme.typography.titleMedium)
                        if (a.reader.dedication.isNotEmpty()) Text(a.reader.dedication, style = MaterialTheme.typography.bodyMedium)
                        val fileCount = remember(a) { a.reader.entries.count { !it.isDir } }
                        Text("$fileCount file(s)", style = MaterialTheme.typography.bodySmall)
                        Button(enabled = !busy, onClick = { treeFor = "extract"; pickTree.launch(null) }) {
                            Text("Extract all to a folder…")
                        }
                        OutlinedTextField(
                            value = filter, onValueChange = { filter = it }, singleLine = true,
                            label = { Text("Search") }, modifier = Modifier.fillMaxWidth()
                        )
                        val shown = remember(a, filter) {
                            if (filter.isBlank()) a.reader.entries
                            else a.reader.entries.filter { it.path.contains(filter, ignoreCase = true) }
                        }
                        LazyColumn(Modifier.weight(1f)) {
                            items(shown) { e ->
                                Column(
                                    Modifier.fillMaxWidth()
                                        .clickable(enabled = !e.isDir && !busy) { pendingEntry = e; saveEntry.launch(e.name) }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text(if (e.isDir) e.path + "/" else e.path, style = MaterialTheme.typography.bodyMedium)
                                    if (!e.isDir) Text(formatSize(e.size) + "  ·  tap to save", style = MaterialTheme.typography.bodySmall)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
