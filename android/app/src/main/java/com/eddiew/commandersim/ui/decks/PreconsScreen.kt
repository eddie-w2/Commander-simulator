package com.eddiew.commandersim.ui.decks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddiew.commandersim.AppViewModel
import com.eddiew.commandersim.ImportPreview
import com.eddiew.commandersim.data.Precon
import com.eddiew.commandersim.ui.components.ColorPips
import com.eddiew.commandersim.ui.components.HudButton
import com.eddiew.commandersim.ui.components.HudChip
import com.eddiew.commandersim.ui.components.HudOutlineButton
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.Notice
import com.eddiew.commandersim.ui.components.ScreenHeader
import com.eddiew.commandersim.ui.theme.Hud
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private sealed interface PreconState {
    data object Loading : PreconState
    data class Ready(val preview: ImportPreview) : PreconState
    data class Failed(val message: String) : PreconState
    data class Added(val deckId: String) : PreconState
}

@Composable
fun PreconsScreen(vm: AppViewModel, onBack: () -> Unit, onOpenDeck: (String) -> Unit) {
    val catalog by vm.precons.collectAsStateWithLifecycle()
    val decks by vm.decks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var owned by rememberSaveable { mutableStateOf(true) }
    var open by remember { mutableStateOf<String?>(null) }
    val states = remember { mutableStateMapOf<String, PreconState>() }
    val addedUrls = decks.mapNotNull { it.sourceUrl }.toSet()

    val all = catalog?.precons.orEmpty()
    val shown = if (query.isBlank()) all else all.filter {
        it.name.contains(query, ignoreCase = true) || it.code.contains(query, ignoreCase = true) || it.year == query
    }

    fun sync() {
        syncing = true
        scope.launch {
            message = vm.syncPrecons()
            syncing = false
        }
    }

    fun toggle(p: Precon) {
        open = if (open == p.fileName) null else p.fileName
        if (open == p.fileName && states[p.fileName] !is PreconState.Ready) {
            states[p.fileName] = PreconState.Loading
            scope.launch {
                states[p.fileName] = try {
                    PreconState.Ready(vm.previewPrecon(p))
                } catch (e: Exception) {
                    PreconState.Failed(e.message ?: "Couldn't load this deck.")
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Precons",
            catalog?.let {
                "${it.precons.size} Commander decks · synced " +
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.syncedAt))
            } ?: "Official Commander precons from MTGJSON",
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudChip("← Back", false, onBack, color = Hud.Muted)
                    HudChip(if (syncing) "Syncing…" else "⟳ Sync list", false, { if (!syncing) sync() }, color = Hud.Cyan)
                }
            }
            message?.let { item { Notice(it, color = Hud.Electric) } }
            if (catalog == null) {
                item {
                    HudPanel(title = "No precon list yet") {
                        Text("Tap Sync list to download the list of every official Commander precon (needs internet once; " +
                            "it's kept on the phone after that and refreshed weekly).",
                            style = MaterialTheme.typography.bodySmall, color = Hud.Muted)
                        Spacer(Modifier.height(8.dp))
                        HudButton(if (syncing) "Syncing…" else "Sync list", { if (!syncing) sync() }, Modifier.fillMaxWidth())
                    }
                }
            } else {
                item {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text("Search name, set code or year") },
                        modifier = Modifier.fillMaxWidth(), colors = hudFieldColors(),
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth().clickable { owned = !owned }, verticalAlignment = Alignment.CenterVertically) {
                        Text("Mark added decks as owned", style = MaterialTheme.typography.bodyMedium, color = Hud.Text, modifier = Modifier.weight(1f))
                        Switch(
                            checked = owned, onCheckedChange = { owned = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Hud.Bg, checkedTrackColor = Hud.Cyan, uncheckedTrackColor = Hud.Grid),
                        )
                    }
                }
                var lastYear: String? = null
                shown.forEach { p ->
                    if (p.year != lastYear) {
                        lastYear = p.year
                        item(key = "year-${p.year}") {
                            Text(p.year, style = MaterialTheme.typography.labelLarge, color = Hud.Electric, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    item(key = p.fileName) {
                        PreconRow(
                            p = p,
                            added = p.url in addedUrls,
                            expanded = open == p.fileName,
                            state = states[p.fileName],
                            onClick = { toggle(p) },
                            onAdd = { preview -> states[p.fileName] = PreconState.Added(vm.saveDeck(p.name, preview, emptyList(), owned)) },
                            onOpenDeck = onOpenDeck,
                        )
                    }
                }
                if (shown.isEmpty()) item { Text("No precons match.", style = MaterialTheme.typography.bodySmall, color = Hud.Dim) }
            }
        }
    }
}

@Composable
private fun PreconRow(
    p: Precon,
    added: Boolean,
    expanded: Boolean,
    state: PreconState?,
    onClick: () -> Unit,
    onAdd: (ImportPreview) -> Unit,
    onOpenDeck: (String) -> Unit,
) {
    HudPanel(accent = if (added) Hud.Positive else if (expanded) Hud.Cyan else Hud.Outline, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, color = Hud.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.code} · ${p.releaseDate ?: "unreleased"}", style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
            }
            if (added) Text("ADDED", style = MaterialTheme.typography.labelSmall, color = Hud.Positive)
            Spacer(Modifier.width(6.dp))
            Text(if (expanded) "▾" else "▸", color = Hud.Electric)
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            when (state) {
                null, PreconState.Loading -> Text("Loading deck…", style = MaterialTheme.typography.bodySmall, color = Hud.Muted)
                is PreconState.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = Hud.Negative)
                is PreconState.Added -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Added to your decks.", style = MaterialTheme.typography.bodySmall, color = Hud.Positive, modifier = Modifier.weight(1f))
                    HudOutlineButton("Open", { onOpenDeck(state.deckId) })
                }
                is PreconState.Ready -> {
                    val pv = state.preview
                    val identity = pv.commanders.mapNotNull { it.colors }.joinToString("").let { c -> "WUBRG".filter { it in c } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            pv.commanders.forEach { Text("◆ ${it.name}", style = MaterialTheme.typography.bodyMedium, color = Hud.Cyan) }
                            if (pv.commanders.isEmpty()) Text("Commander not marked; you can pick it after adding.",
                                style = MaterialTheme.typography.bodySmall, color = Hud.Warn)
                            Text("${pv.total} cards", style = MaterialTheme.typography.labelSmall, color = Hud.Muted)
                        }
                        ColorPips(identity)
                    }
                    pv.warnings.filterNot { it.startsWith("No commander") }.forEach {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = Hud.Warn)
                    }
                    Spacer(Modifier.height(8.dp))
                    HudButton(if (added) "Add another copy" else "Add deck", { onAdd(pv) }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
