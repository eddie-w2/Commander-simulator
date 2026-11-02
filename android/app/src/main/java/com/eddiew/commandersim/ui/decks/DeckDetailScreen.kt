package com.eddiew.commandersim.ui.decks

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddiew.commandersim.AppViewModel
import com.eddiew.commandersim.data.model.CardType
import com.eddiew.commandersim.data.model.DeckVersion
import com.eddiew.commandersim.data.model.diffDecks
import com.eddiew.commandersim.ui.components.HudChip
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import com.eddiew.commandersim.ui.components.HudButton
import com.eddiew.commandersim.ui.components.ColorPips
import com.eddiew.commandersim.ui.components.ColumnChart
import com.eddiew.commandersim.ui.components.DonutChart
import com.eddiew.commandersim.ui.components.HudOutlineButton
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.Notice
import com.eddiew.commandersim.ui.components.ScreenHeader
import com.eddiew.commandersim.ui.components.TileRow
import com.eddiew.commandersim.ui.components.num
import com.eddiew.commandersim.ui.components.cardPress
import com.eddiew.commandersim.ui.theme.Hud
import com.eddiew.commandersim.sim.ManaBase
import com.eddiew.commandersim.ui.results.ManaBaseView

@Composable
fun DeckDetailScreen(vm: AppViewModel, deckId: String, onBack: () -> Unit, onVariant: (String) -> Unit, onRunAb: () -> Unit) {
    val decks by vm.decks.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf<DeckVersion?>(null) }
    var showAllVersions by remember { mutableStateOf(false) }
    val deck = decks.firstOrNull { it.id == deckId }
    var confirmDelete by remember { mutableStateOf(false) }

    if (deck == null) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Deck not found")
            HudOutlineButton("Back", onBack, Modifier.padding(16.dp))
        }
        return
    }

    val all = deck.commanders + deck.cards
    val manaBase = remember(deck) { ManaBase.analyze(deck) }
    val byType = all.groupBy { it.type }.mapValues { (_, v) -> v.sumOf { it.quantity } }
    val nonLand = all.filter { it.type != CardType.LAND && it.manaValue != null }
    val curve = List(8) { mv -> nonLand.filter { minOf(it.manaValue!!.toInt(), 7) == mv }.sumOf { it.quantity }.toDouble() }
    val avgMv = nonLand.sumOf { it.manaValue!! * it.quantity } / nonLand.sumOf { it.quantity }.coerceAtLeast(1)

    Column(Modifier.fillMaxSize()) {
    val parent = deck.parentId?.let { pid -> decks.firstOrNull { it.id == pid } }
    ScreenHeader(deck.name, deck.commanderLabel.takeIf { !it.equals(deck.name, ignoreCase = true) }, trailing = { ColorPips(deck.colorIdentity) })
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudOutlineButton("← Back", onBack, color = Hud.Muted)
                HudOutlineButton(if (deck.id in syncing) "Syncing…" else "Re-sync Scryfall", { vm.resync(deck.id) })
                HudOutlineButton("Delete", { confirmDelete = true }, color = Hud.Negative)
            }
        }
        item {
            HudChip(if (deck.owned) "✓ Owned" else "Mark as owned", deck.owned, { vm.setOwned(deck.id, !deck.owned) }, color = Hud.Cyan)
        }
        if (deck.sourceUrl != null) {
            item {
                HudPanel(title = "Linked deck", accent = Hud.Electric) {
                    Text(deck.sourceUrl, style = MaterialTheme.typography.bodySmall, color = Hud.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        deck.syncedAt?.let { "Last synced " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
                            ?: "Not synced yet",
                        style = MaterialTheme.typography.labelSmall, color = Hud.Dim,
                    )
                    Spacer(Modifier.height(8.dp))
                    HudButton(
                        if (deck.id in syncing) "Syncing…" else "Sync from link",
                        { scope.launch { syncMessage = vm.syncDeck(deck.id).message } },
                        Modifier.fillMaxWidth(), enabled = deck.id !in syncing,
                    )
                    syncMessage?.let {
                        Spacer(Modifier.height(6.dp))
                        Notice(it, color = Hud.Electric)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("The current list is saved under Versions before any change, so you can switch back.",
                        style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
                }
            }
        }
        val versions = history[deck.id].orEmpty()
        if (versions.isNotEmpty()) {
            item {
                HudPanel(title = "Versions · ${versions.size} saved") {
                    val shown = if (showAllVersions) versions else versions.take(5)
                    shown.forEach { v ->
                        val diff = diffDecks(v.asDeck(deck), deck)
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(v.reason, style = MaterialTheme.typography.bodyMedium, color = Hud.Text)
                                Text(
                                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(v.savedAt)) +
                                        " · ${v.cardCount} cards · " +
                                        if (diff.added.isEmpty() && diff.removed.isEmpty()) "same as now"
                                        else "now +${diff.added.sumOf { it.quantity }} / −${diff.removed.sumOf { it.quantity }}",
                                    style = MaterialTheme.typography.labelSmall, color = Hud.Dim,
                                )
                            }
                            if (diff.added.isNotEmpty() || diff.removed.isNotEmpty()) {
                                HudOutlineButton("Restore", { confirmRestore = v })
                            }
                        }
                    }
                    if (!showAllVersions && versions.size > 5) {
                        HudOutlineButton("Show all ${versions.size}", { showAllVersions = true }, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        item {
            HudPanel(title = "A/B test card swaps", accent = Hud.Cyan) {
                Text(
                    "Make a copy of this deck with some cards swapped, then simulate both versions on identical games " +
                        "to see what the swap changes.",
                    style = MaterialTheme.typography.bodySmall, color = Hud.Muted,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("Create variant", { onVariant(deck.id) }, Modifier.weight(1f))
                    if (parent != null) {
                        HudOutlineButton("Test vs original", { vm.startAb(parent.id, deck.id); onRunAb() })
                    }
                }
            }
        }
        if (parent != null) {
            item {
                val diff = diffDecks(parent, deck)
                HudPanel(title = "Changes vs ${parent.name}") {
                    if (diff.removed.isEmpty() && diff.added.isEmpty()) {
                        Text("No differences yet.", style = MaterialTheme.typography.bodySmall, color = Hud.Dim)
                    }
                    diff.removed.forEach {
                        Text("− ${if (it.quantity > 1) "${it.quantity} " else ""}${it.name}", style = MaterialTheme.typography.bodyMedium, color = Hud.Negative)
                    }
                    diff.added.forEach {
                        Text("+ ${if (it.quantity > 1) "${it.quantity} " else ""}${it.name}", style = MaterialTheme.typography.bodyMedium, color = Hud.Positive)
                    }
                }
            }
        }
        if (!deck.enriched) {
            item { Notice("Card types and mana values aren't loaded yet. They come from Scryfall and need an internet connection.") }
        }
        item {
            TileRow(
                Triple("Cards", "${deck.cardCount}", null),
                Triple("Lands", "${byType[CardType.LAND] ?: 0}", null),
                Triple("Avg MV", if (nonLand.isEmpty()) "—" else num(avgMv, 2), "non-land"),
            )
        }
        item {
            HudPanel(title = "Type breakdown") {
                DonutChart(
                    byType.entries.sortedByDescending { it.value }.map { it.key.label to it.value.toDouble() },
                    centerTop = "${deck.cardCount}", centerBottom = "cards",
                )
            }
        }
        if (nonLand.isNotEmpty()) {
            item {
                HudPanel(title = "Mana curve") {
                    ColumnChart(curve, List(8) { if (it == 7) "7+" else "$it" })
                }
            }
        }
        item { DeckCategoriesPanel(deck) }
        item { ManaBaseView(manaBase, showCurve = false, showRoles = false) }
        CardType.entries.filter { byType.containsKey(it) }.forEach { type ->
            item {
                HudPanel(title = "${type.label} · ${byType[type]}") {
                    all.filter { it.type == type }.sortedWith(compareBy({ it.manaValue ?: 99.0 }, { it.name })).forEach { c ->
                        Row(Modifier.fillMaxWidth().cardPress(c.name).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${c.quantity}×", style = MaterialTheme.typography.labelMedium, color = Hud.Dim, modifier = Modifier.width(30.dp))
                            Text(
                                c.name, style = MaterialTheme.typography.bodyMedium,
                                color = if (deck.commanders.contains(c)) Hud.Cyan else Hud.Text,
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            c.manaValue?.let { Text(num(it, 0), style = MaterialTheme.typography.labelMedium, color = Hud.Electric) }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
    }

    confirmRestore?.let { v ->
        val diff = diffDecks(deck, v.asDeck(deck))
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            containerColor = Hud.SurfaceHi,
            title = { Text("Restore this version?", color = Hud.Text) },
            text = {
                Column {
                    Text("${v.reason} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(v.savedAt))}", color = Hud.Muted)
                    Spacer(Modifier.height(6.dp))
                    diff.removed.take(8).forEach { Text("− ${it.name}", color = Hud.Negative) }
                    diff.added.take(8).forEach { Text("+ ${it.name}", color = Hud.Positive) }
                    if (diff.removed.size + diff.added.size > 16) Text("…", color = Hud.Dim)
                    Spacer(Modifier.height(6.dp))
                    Text("The current list is saved as a version first.", color = Hud.Dim)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.restoreVersion(deck.id, v.versionId); confirmRestore = null }) { Text("RESTORE", color = Hud.Cyan) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("CANCEL", color = Hud.Muted) } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Hud.SurfaceHi,
            title = { Text("Delete ${deck.name}?", color = Hud.Text) },
            text = { Text("This removes the deck from the app. Past results that include it are kept.", color = Hud.Muted) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteDeck(deck.id); onBack() }) { Text("DELETE", color = Hud.Negative) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("CANCEL", color = Hud.Muted) } },
        )
    }
}
