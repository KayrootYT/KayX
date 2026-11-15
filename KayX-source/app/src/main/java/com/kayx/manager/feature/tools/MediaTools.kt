package com.kayx.manager.feature.tools

import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kayx.manager.ui.theme.Accent
import com.kayx.manager.ui.theme.Fg
import kotlinx.coroutines.delay
import java.io.File

private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr", "mid", "midi", "wma", "3gp")

private fun clock(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Music player with repeat, previous/next and a playlist of the audio files in the same folder. */
@Composable
fun AudioPlayerScreen(path: String, onBack: () -> Unit) {
    val file = remember(path) { File(path) }
    val playlist = remember(path) {
        file.parentFile?.listFiles()?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXT }?.sortedBy { it.name.lowercase() }
            ?.ifEmpty { null } ?: listOf(file)
    }
    var index by remember { mutableStateOf(playlist.indexOfFirst { it.path == path }.coerceAtLeast(0)) }
    val player = remember { MediaPlayer() }
    var error by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableStateOf(0) }
    var position by remember { mutableStateOf(0) }
    var repeat by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }

    fun load(i: Int) {
        index = i; error = null; position = 0; playing = false
        runCatching {
            player.reset()
            player.setDataSource(playlist[i].absolutePath)
            player.setOnPreparedListener { duration = it.duration; it.start(); playing = true }
            player.setOnCompletionListener {
                if (repeat) { it.seekTo(0); it.start() }
                else if (index < playlist.lastIndex) load(index + 1) else playing = false
            }
            player.setOnErrorListener { _, w, e -> error = "Playback failed (what=$w, extra=$e)"; playing = false; true }
            player.prepareAsync()
        }.onFailure { error = it.message ?: "Cannot play this file" }
    }

    DisposableEffect(Unit) { load(index); onDispose { runCatching { player.release() } } }
    LaunchedEffect(playing) {
        while (playing) {
            if (!dragging) position = runCatching { player.currentPosition }.getOrDefault(0)
            delay(300)
        }
    }

    ToolScaffold("Music player", onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(10.dp))
            Text(playlist[index].name, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
            error?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(220.dp).background(Fg.copy(.08f), CircleShape), Alignment.Center) {
                Icon(Icons.Default.MusicNote, null, Modifier.size(110.dp), tint = Fg.copy(.75f))
            }
            Spacer(Modifier.weight(1f))
            Slider(
                value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                onValueChange = { dragging = true; position = it.toInt() },
                onValueChangeFinished = { runCatching { player.seekTo(position) }; dragging = false },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock(position), style = MaterialTheme.typography.labelMedium); Text(clock(duration), style = MaterialTheme.typography.labelMedium)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton({ repeat = !repeat }) { Icon(if (repeat) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat", tint = if (repeat) Accent else Fg) }
                IconButton({ if (index > 0) load(index - 1) }) { Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(34.dp)) }
                IconButton({
                    runCatching { if (player.isPlaying) { player.pause(); playing = false } else { player.start(); playing = true } }
                }, Modifier.size(64.dp)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", Modifier.size(46.dp), tint = Accent) }
                IconButton({ if (index < playlist.lastIndex) load(index + 1) }) { Icon(Icons.Default.SkipNext, "Next", Modifier.size(34.dp)) }
                IconButton({ showList = true }) { Icon(Icons.Default.QueueMusic, "Playlist") }
            }
        }
    }
    if (showList) AlertDialog({ showList = false }, title = { Text("Playlist (${playlist.size})") }, text = {
        LazyColumn(Modifier.heightIn(max = 380.dp)) {
            itemsIndexed(playlist) { i, f ->
                Text(f.name, Modifier.fillMaxWidth().clickable { showList = false; load(i) }.padding(vertical = 10.dp),
                    color = if (i == index) Accent else Fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }, confirmButton = { TextButton({ showList = false }) { Text("Close") } })
}

@Composable
private fun VideoSurface(source: String, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    key(source) {
        AndroidView(modifier = modifier, factory = { c ->
            VideoView(c).apply {
                val mc = MediaController(c)
                mc.setAnchorView(this)
                setMediaController(mc)
                if (source.startsWith("http://") || source.startsWith("https://") || source.startsWith("rtsp://")) setVideoURI(Uri.parse(source))
                else setVideoPath(source)
                setOnErrorListener { _, w, e -> onError("Cannot play (what=$w, extra=$e)"); true }
                setOnPreparedListener { start() }
            }
        }, onRelease = { it.stopPlayback() })
    }
}

@Composable
fun VideoPlayerScreen(path: String, onBack: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    ToolScaffold(File(path).name, onBack) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
            VideoSurface(path, { error = it }, Modifier.fillMaxSize())
            error?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFFF6B6B)) }
        }
    }
}

/** Plays a network stream (http/https/rtsp) in a VideoView; works for audio streams too. */
@Composable
fun StreamPlayerScreen(onBack: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var active by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    ToolScaffold("Streaming media player", onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(url, { url = it }, Modifier.weight(1f), singleLine = true, label = { Text("Stream URL (http, https, rtsp)") })
                IconButton({ error = null; active = url.trim().ifEmpty { null } }) { Icon(Icons.Default.PlayArrow, "Play", tint = Accent) }
            }
            error?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFFF6B6B)) }
            active?.let { VideoSurface(it, { e -> error = e }, Modifier.fillMaxWidth().weight(1f)) }
        }
    }
}

@Composable
fun FontViewerScreen(path: String, onBack: () -> Unit) {
    val tf = remember(path) { runCatching { android.graphics.Typeface.createFromFile(path) }.getOrNull() }
    ToolScaffold(File(path).name, onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (tf == null) Text("Cannot load this font file") else {
                val ff = FontFamily(Typeface(tf))
                Text("The quick brown fox jumps over the lazy dog", style = TextStyle(fontFamily = ff, fontSize = 28.sp, color = Fg))
                Text("ABCDEFGHIJKLMNOPQRSTUVWXYZ", style = TextStyle(fontFamily = ff, fontSize = 22.sp, color = Fg))
                Text("abcdefghijklmnopqrstuvwxyz", style = TextStyle(fontFamily = ff, fontSize = 22.sp, color = Fg))
                Text("0123456789 !@#\$%^&*()[]{}<>?/", style = TextStyle(fontFamily = ff, fontSize = 22.sp, color = Fg))
                listOf(12, 16, 20, 32, 48, 64).forEach { sz ->
                    Text("Aa Bb Cc 123  ($sz sp)", style = TextStyle(fontFamily = ff, fontSize = sz.sp, color = Fg), maxLines = 1, overflow = TextOverflow.Clip)
                }
            }
        }
    }
}
