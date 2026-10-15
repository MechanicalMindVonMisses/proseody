package dev.aliberke.sesli.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aliberke.sesli.MainViewModel
import dev.aliberke.sesli.ReaderContent
import dev.aliberke.sesli.neural.ModelStatus
import dev.aliberke.sesli.neural.NeuralCatalog
import dev.aliberke.sesli.neural.NeuralModel
import dev.aliberke.sesli.neural.NeuralVoice
import dev.aliberke.sesli.text.Segmenter
import dev.aliberke.sesli.text.Span
import dev.aliberke.sesli.tts.PlaybackState
import dev.aliberke.sesli.tts.VoiceOption
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(vm: MainViewModel, docId: Long, snackbar: SnackbarHostState, onBack: () -> Unit) {
    LaunchedEffect(docId) { vm.openReader(docId) }
    val content by vm.reader.collectAsStateWithLifecycle()
    val state by vm.engine.state.collectAsStateWithLifecycle()
    val reader = content?.takeIf { it.doc.id == docId && state.docId == docId }
    var showSpeed by remember { mutableStateOf(false) }
    var showVoices by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val togglePlay = {
        if (Build.VERSION.SDK_INT >= 33 && !vm.prefs.askedNotifications &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            vm.prefs.askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.engine.togglePlay()
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.engine.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(reader?.doc?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { showVoices = true }) { Icon(Icons.Filled.RecordVoiceOver, contentDescription = "Voice") }
                },
            )
        },
        bottomBar = {
            if (reader != null) {
                PlayerBar(
                    state = state,
                    onToggle = togglePlay,
                    onPrevious = vm.engine::previousSentence,
                    onNext = vm.engine::nextSentence,
                    onSpeed = { showSpeed = true },
                    onSeek = { fraction -> vm.engine.seekTo((fraction * state.length).toInt()) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (reader == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            ReaderText(reader, state, padding, onTap = vm.engine::seekTo)
        }
    }

    if (showSpeed) SpeedSheet(state.rate, onDismiss = { showSpeed = false }, onRate = vm.engine::setRate)
    if (showVoices) VoiceSheet(vm, state, onDismiss = { showVoices = false })
}

@Composable
private fun ReaderText(content: ReaderContent, state: PlaybackState, padding: PaddingValues, onTap: (Int) -> Unit) {
    val paragraphs = content.paragraphs
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = Segmenter.indexAt(paragraphs, state.position).coerceAtLeast(0),
    )
    var follow by remember { mutableStateOf(true) }
    val currentParagraph = state.sentence?.let { Segmenter.indexAt(paragraphs, it.start) } ?: -1

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
    }
    LaunchedEffect(currentParagraph, follow) {
        if (!follow || currentParagraph < 0) return@LaunchedEffect
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == currentParagraph }
        val comfortablyVisible = item != null && item.offset >= 0 && item.offset + item.size <= layout.viewportEndOffset - 120
        if (!comfortablyVisible) listState.animateScrollToItem(currentParagraph)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 22.dp, end = 22.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(paragraphs, key = { i, _ -> i }) { _, para ->
                Paragraph(
                    text = content.text,
                    para = para,
                    isHeading = isHeading(content.text, para),
                    sentence = state.sentence?.intersect(para),
                    word = state.word?.intersect(para),
                    onTap = onTap,
                )
            }
        }
        if (!follow && currentParagraph >= 0) {
            ExtendedFloatingActionButton(
                onClick = { follow = true },
                icon = { Icon(Icons.Filled.MyLocation, contentDescription = null) },
                text = { Text("Back to reading") },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = padding.calculateBottomPadding() + 16.dp),
            )
        }
    }
}

private fun isHeading(text: String, para: Span): Boolean {
    if (para.length !in 2..80 || text[para.end - 1] in ".!?,;:…\"”'’)") return false
    val line = text.substring(para.start, para.end)
    return line.first().isUpperCase() && '@' !in line && "://" !in line && line.count { it == ' ' } < 10
}

@Composable
private fun Paragraph(text: String, para: Span, isHeading: Boolean, sentence: Span?, word: Span?, onTap: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val sentenceColor = colors.primaryContainer
    val wordBackground = colors.primary
    val wordColor = colors.onPrimary
    val annotated = remember(para, sentence, word, sentenceColor, wordBackground) {
        buildAnnotatedString {
            append(text, para.start, para.end)
            sentence?.let { addStyle(SpanStyle(background = sentenceColor), it.start - para.start, it.end - para.start) }
            word?.let { addStyle(SpanStyle(background = wordBackground, color = wordColor), it.start - para.start, it.end - para.start) }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val base = if (isHeading) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge
    Text(
        text = annotated,
        style = base.copy(
            fontSize = if (isHeading) 22.sp else 19.sp,
            lineHeight = if (isHeading) 30.sp else 30.sp,
            fontWeight = if (isHeading) FontWeight.SemiBold else FontWeight.Normal,
        ),
        onTextLayout = { layout = it },
        modifier = Modifier.fillMaxWidth().pointerInput(para) {
            detectTapGestures { offset -> layout?.let { onTap(para.start + it.getOffsetForPosition(offset)) } }
        },
    )
}

@Composable
private fun PlayerBar(
    state: PlaybackState,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSpeed: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = if (state.length > 0) state.position.toFloat() / state.length else 0f
    val shown = dragging ?: fraction
    Surface(tonalElevation = 3.dp, shadowElevation = 12.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Slider(
                value = shown,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let(onSeek)
                    dragging = null
                },
            )
            Row(Modifier.fillMaxWidth()) {
                val elapsed = listeningSeconds((shown * state.length).toInt(), state.rate)
                val remaining = listeningSeconds(((1 - shown) * state.length).toInt(), state.rate)
                Text(formatDuration(elapsed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("-${formatDuration(remaining)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSpeed, modifier = Modifier.width(72.dp)) {
                    Text(speedLabel(state.rate), style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onClick = onPrevious, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous sentence", Modifier.size(32.dp))
                }
                FilledIconButton(onClick = onToggle, modifier = Modifier.size(68.dp)) {
                    Icon(
                        if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playing) "Pause" else "Play",
                        modifier = Modifier.size(38.dp),
                    )
                }
                IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next sentence", Modifier.size(32.dp))
                }
                Spacer(Modifier.width(72.dp))
            }
        }
    }
}

private fun speedLabel(rate: Float) = "%.1f×".format(Locale.US, rate).replace(".0×", "×")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSheet(rate: Float, onDismiss: () -> Unit, onRate: (Float) -> Unit) {
    var value by remember { mutableFloatStateOf(rate) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Speed  ${speedLabel(value)}", style = MaterialTheme.typography.titleLarge)
            Text(
                "≈ ${(value * 160).roundToInt()} words per minute",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Slider(
                value = value,
                onValueChange = { value = (it * 10).roundToInt() / 10f },
                onValueChangeFinished = { onRate(value) },
                valueRange = 0.5f..4f,
                steps = 34,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.8f, 1f, 1.2f, 1.5f, 2f, 2.5f, 3f).forEach { preset ->
                    FilterChip(
                        selected = value == preset,
                        onClick = {
                            value = preset
                            onRate(preset)
                        },
                        label = { Text(speedLabel(preset)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSheet(vm: MainViewModel, state: PlaybackState, onDismiss: () -> Unit) {
    val voices by vm.engine.voices.collectAsStateWithLifecycle()
    val engines by vm.engine.engines.collectAsStateWithLifecycle()
    val modelStatus by vm.engine.models.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val docLanguage = Locale.forLanguageTag(state.language ?: Locale.getDefault().toLanguageTag()).language
    var allLanguages by remember { mutableStateOf(false) }
    val shown = if (allLanguages) voices else voices.filter { it.locale.language == docLanguage }
    val models = NeuralCatalog.models.filter { allLanguages || it.language == docLanguage }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("Voice", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Show all languages", Modifier.weight(1f))
                Switch(checked = allLanguages, onCheckedChange = { allLanguages = it })
            }
        }
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            if (models.isNotEmpty()) item(key = "neural-header") { SectionLabel("Neural voices · offline") }
            for (model in models) {
                val status = modelStatus[model.id] ?: ModelStatus.NotInstalled
                if (status == ModelStatus.Installed) {
                    items(NeuralCatalog.voices.filter { it.model == model }, key = { it.id }) { voice ->
                        NeuralVoiceRow(voice, selected = voice.id == state.voiceName) { vm.engine.previewVoice(voice.id) }
                    }
                    item(key = "remove-${model.id}") {
                        TextButton(onClick = { vm.engine.models.delete(model) }, modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text("Remove ${model.title} (${model.sizeMb} MB)")
                        }
                    }
                } else {
                    item(key = "model-${model.id}") {
                        ModelDownloadRow(
                            model, status,
                            onDownload = { vm.engine.models.download(model) },
                            onCancel = { vm.engine.models.cancel(model) },
                        )
                    }
                }
            }
            item(key = "system-header") {
                Column {
                    SectionLabel("System voices")
                    if (engines.size > 1) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            engines.forEach { engine ->
                                FilterChip(
                                    selected = engine.packageName == state.enginePackage,
                                    onClick = { vm.engine.setEngine(engine.packageName) },
                                    label = { Text(engine.label) },
                                )
                            }
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (voices.isEmpty()) "Loading voices…" else "No voices for this language. Download one below or show all languages.",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(shown, key = { _, v -> v.name }) { _, voice ->
                VoiceRow(voice, selected = voice.name == state.voiceName) { vm.engine.previewVoice(voice.name) }
            }
        }
        TextButton(
            onClick = {
                val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                    state.enginePackage?.let { setPackage(it) }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }
            },
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 24.dp),
        ) {
            Icon(Icons.Filled.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Download more voices")
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun NeuralVoiceRow(voice: NeuralVoice, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(voice.label) },
        supportingContent = { Text("${voice.model.title} · neural · offline") },
        leadingContent = { Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary) },
    )
}

@Composable
private fun ModelDownloadRow(model: NeuralModel, status: ModelStatus, onDownload: () -> Unit, onCancel: () -> Unit) {
    val language = Locale.forLanguageTag(model.language).getDisplayLanguage(Locale.getDefault())
    Column {
        ListItem(
            leadingContent = { Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text("${model.title} · $language") },
            supportingContent = {
                Text(
                    when (status) {
                        is ModelStatus.Downloading -> "Downloading… ${(status.progress * 100).toInt()}%"
                        is ModelStatus.Failed -> "Download failed: ${status.message}"
                        else -> "${model.description} · ${model.sizeMb} MB download"
                    }
                )
            },
            trailingContent = {
                if (status is ModelStatus.Downloading) {
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                } else {
                    IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, contentDescription = "Download") }
                }
            },
        )
        if (status is ModelStatus.Downloading) {
            LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        }
    }
}

@Composable
private fun VoiceRow(voice: VoiceOption, selected: Boolean, onClick: () -> Unit) {
    val variant = voice.name.substringAfter("-x-", "").substringBefore('-').ifEmpty { voice.name }
    val quality = when {
        voice.quality >= Voice.QUALITY_VERY_HIGH -> "Very high quality"
        voice.quality >= Voice.QUALITY_HIGH -> "High quality"
        voice.quality >= Voice.QUALITY_NORMAL -> "Normal quality"
        else -> "Basic"
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text("${voice.locale.getDisplayName(Locale.getDefault())} · $variant") },
        supportingContent = { Text(if (voice.network) "$quality · needs internet" else "$quality · offline") },
        leadingContent = {
            if (voice.network) Icon(Icons.Filled.CloudQueue, contentDescription = null)
            else Icon(Icons.Filled.RecordVoiceOver, contentDescription = null)
        },
        trailingContent = { if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary) },
    )
}
