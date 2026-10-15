package dev.aliberke.sesli.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aliberke.sesli.MainViewModel
import dev.aliberke.sesli.data.Document
import dev.aliberke.sesli.data.progressFraction
import dev.aliberke.sesli.tts.PlaybackState

private enum class AddDialog { None, Paste, Link }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: MainViewModel, snackbar: SnackbarHostState, onOpen: (Long) -> Unit) {
    val docs by vm.documents.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val playback by vm.engine.state.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf(AddDialog.None) }
    var renaming by remember { mutableStateOf<Document?>(null) }
    var deleting by remember { mutableStateOf<Document?>(null) }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris)
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris, "image/*")
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri
        if (saved && uri != null) vm.importImages(listOf(uri), null)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Proseody") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add") },
            )
        },
        bottomBar = {
            if (playback.docId != null) {
                MiniPlayer(playback, onOpen = { playback.docId?.let(onOpen) }, onToggle = vm.engine::togglePlay)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (docs.isEmpty()) {
                EmptyLibrary(Modifier.weight(1f))
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search library") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val shown = if (query.isBlank()) docs else docs.filter { it.title.contains(query.trim(), ignoreCase = true) }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(shown, key = { it.id }) { doc ->
                        DocumentRow(
                            doc = doc,
                            nowPlaying = playback.docId == doc.id && playback.playing,
                            onClick = { onOpen(doc.id) },
                            onRename = { renaming = doc },
                            onDelete = { deleting = doc },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddSheet(
            onDismiss = { showAdd = false },
            onPaste = { dialog = AddDialog.Paste },
            onLink = { dialog = AddDialog.Link },
            onFiles = {
                pickFiles.launch(
                    arrayOf(
                        "application/pdf", "application/epub+zip",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "text/*", "image/*", "application/octet-stream",
                    )
                )
            },
            onPhotos = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onCamera = {
                val uri = vm.newCameraUri()
                cameraUri = uri
                takePhoto.launch(uri)
            },
        )
    }

    when (dialog) {
        AddDialog.Paste -> PasteDialog(onDismiss = { dialog = AddDialog.None }) { title, text ->
            dialog = AddDialog.None
            vm.importText(title, text)
        }
        AddDialog.Link -> LinkDialog(onDismiss = { dialog = AddDialog.None }) { url ->
            dialog = AddDialog.None
            vm.importUrl(url)
        }
        AddDialog.None -> Unit
    }

    renaming?.let { doc ->
        var title by remember(doc.id) { mutableStateOf(doc.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(title, { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = { vm.rename(doc, title); renaming = null }, enabled = title.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { doc ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete?") },
            text = { Text("\"${doc.title}\" will be removed from your library.") },
            confirmButton = { TextButton(onClick = { vm.delete(doc); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Headphones, contentDescription = null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Your library is empty", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap Add to paste text, open a web article, import a PDF, EPUB or Word file, or scan a page with your camera. " +
                "You can also share text, links and files to Proseody from other apps.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun sourceIcon(source: String): ImageVector = when (source) {
    "web" -> Icons.Filled.Public
    "pdf" -> Icons.Filled.PictureAsPdf
    "epub" -> Icons.AutoMirrored.Filled.MenuBook
    "docx" -> Icons.Filled.Description
    "image" -> Icons.Filled.Image
    "html", "markdown" -> Icons.AutoMirrored.Filled.Article
    else -> Icons.AutoMirrored.Filled.Notes
}

private fun sourceLabel(source: String): String = when (source) {
    "web" -> "Web"
    "pdf" -> "PDF"
    "epub" -> "EPUB"
    "docx" -> "Word"
    "image" -> "Scan"
    "html" -> "HTML"
    "markdown" -> "Markdown"
    else -> "Text"
}

@Composable
private fun DocumentRow(doc: Document, nowPlaying: Boolean, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val fraction = doc.progressFraction()
    val minutes = (listeningSeconds(doc.length) / 60).coerceAtLeast(1)
    val status = when {
        nowPlaying -> "Playing"
        fraction >= 0.995f -> "Finished"
        fraction > 0f -> "${(fraction * 100).toInt()}%"
        else -> "New"
    }
    Column(Modifier.clickable(onClick = onClick)) {
        ListItem(
            leadingContent = { Icon(sourceIcon(doc.source), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(doc.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text("${sourceLabel(doc.source)} · $minutes min · $status") },
            trailingContent = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = { menu = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            },
        )
        if (fraction > 0f) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp).height(3.dp),
            )
        }
    }
}

@Composable
private fun MiniPlayer(state: PlaybackState, onOpen: () -> Unit, onToggle: () -> Unit) {
    Surface(tonalElevation = 6.dp, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.navigationBarsPadding()) {
            LinearProgressIndicator(
                progress = { if (state.length > 0) state.position.toFloat() / state.length else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(state.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onToggle) {
                    Icon(if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (state.playing) "Pause" else "Play")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(
    onDismiss: () -> Unit,
    onPaste: () -> Unit,
    onLink: () -> Unit,
    onFiles: () -> Unit,
    onPhotos: () -> Unit,
    onCamera: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Add to library", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        AddOption(Icons.Filled.ContentPaste, "Paste text", "Type or paste anything") { onDismiss(); onPaste() }
        AddOption(Icons.Filled.Public, "Web article", "Paste a link to a page or PDF") { onDismiss(); onLink() }
        AddOption(Icons.Filled.FolderOpen, "Files", "PDF, EPUB, Word, TXT, Markdown, HTML") { onDismiss(); onFiles() }
        AddOption(Icons.Filled.PhotoLibrary, "Photos", "Read the text in your pictures") { onDismiss(); onPhotos() }
        AddOption(Icons.Filled.PhotoCamera, "Scan a page", "Take a photo of printed text") { onDismiss(); onCamera() }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AddOption(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
    )
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf(clipboard.getText()?.text.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste text") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("Title (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(body, { body = it }, label = { Text("Text") }, minLines = 6, maxLines = 12, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(title, body) }, enabled = body.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LinkDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val clip = clipboard.getText()?.text?.trim().orEmpty()
    var url by rememberSaveable { mutableStateOf(if (clip.startsWith("http") && ' ' !in clip) clip else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Web article") },
        text = {
            OutlinedTextField(url, { url = it }, label = { Text("Link") }, placeholder = { Text("https://…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onAdd(url) }, enabled = url.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
