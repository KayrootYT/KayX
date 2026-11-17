package com.kayx.manager.feature.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kayx.manager.data.apk.ArscParser
import com.kayx.manager.data.apk.ArscResult
import com.kayx.manager.data.archive.ArchiveEdit
import com.kayx.manager.ui.glass.GlassCard
import com.kayx.manager.ui.theme.Accent
import com.kayx.manager.ui.theme.Fg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Browse compiled resources (strings, colors, dimens, ids...) of a resources.arsc file or an APK. Tap a row to copy it. */
@Composable
fun ArscViewerScreen(path: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val file = remember(path) { File(path) }
    val state by produceState<Result<ArscResult>?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = if (file.extension.equals("arsc", true)) file.readBytes() else ArchiveEdit.readEntry(file, "resources.arsc")
                ArscParser.parse(bytes).getOrThrow()
            }
        }
    }
    var filter by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("All") }

    ToolScaffold("Resources · ${file.name}", onBack) { pad ->
        val res = state
        when {
            res == null -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { CircularProgressIndicator(color = Accent) }
            res.isFailure -> Box(Modifier.fillMaxSize().padding(pad).padding(24.dp), Alignment.Center) {
                Text(res.exceptionOrNull()?.message ?: "Could not read resources", color = Fg.copy(.7f))
            }
            else -> {
                val r = res.getOrThrow()
                val types = remember(r) { listOf("All") + r.entries.map { it.type }.distinct() }
                val shown = remember(r, filter, type) {
                    r.entries.filter { e ->
                        (type == "All" || e.type == type) &&
                            (filter.isBlank() || e.name.contains(filter, true) || e.value.contains(filter, true))
                    }
                }
                Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${r.packageName.ifEmpty { "(package)" }} · ${r.entries.size} resources" + if (r.truncated) " (limited)" else "",
                        style = MaterialTheme.typography.labelMedium, color = Accent
                    )
                    OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search name or value") })
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        types.forEach { t -> FilterChip(type == t, { type = t }, { Text(t) }) }
                    }
                    Text("${shown.size} shown" + if (shown.size > 3000) " · first 3,000" else "", fontSize = 11.sp, color = Fg.copy(.55f))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                        items(shown.take(3000)) { e ->
                            GlassCard(Modifier.fillMaxWidth(), onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("resource", "${e.type}/${e.name} = ${e.value}"))
                                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                            }) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(e.name, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(e.type, fontSize = 11.sp, color = Accent)
                                    }
                                    Text(e.value, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Fg.copy(.9f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    Text("${e.config}  ·  0x%08x".format(e.resId), fontSize = 10.sp, color = Fg.copy(.45f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
