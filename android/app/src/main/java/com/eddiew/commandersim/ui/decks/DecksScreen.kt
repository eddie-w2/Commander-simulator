package com.eddiew.commandersim.ui.decks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddiew.commandersim.AppViewModel
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.ui.components.ColorPips
import com.eddiew.commandersim.ui.components.HudButton
import com.eddiew.commandersim.ui.components.HudChip
import com.eddiew.commandersim.ui.components.HudOutlineButton
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.ScreenHeader
import com.eddiew.commandersim.ui.theme.Hud

@Composable
fun DecksScreen(vm: AppViewModel, onImport: () -> Unit, onOpen: (String) -> Unit, onSettings: () -> Unit, onPrecons: () -> Unit, onMoxfield: () -> Unit) {
    val decks by vm.decks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val names = decks.associate { it.id to it.name }
    var ownedOnly by rememberSaveable { mutableStateOf(false) }
    val shown = if (ownedOnly) decks.filter { it.owned } else decks

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Deck Bay",
            "${decks.size} decks · ${decks.count { it.owned }} owned · ${settings.pool.count { id -> decks.any { it.id == id } }} in pool",
            trailing = {
                if (update != null) HudChip("⚙ Update", true, onSettings, color = Hud.Cyan)
                else HudChip("⚙", false, onSettings, color = Hud.Muted)
            },
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("+ Import deck", onImport, Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HudOutlineButton("Precons", onPrecons, Modifier.weight(1f), color = Hud.Cyan)
                        HudOutlineButton("Moxfield profile", onMoxfield, Modifier.weight(1f), color = Hud.Cyan)
                    }
                }
            }
            if (decks.isNotEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudChip("All decks", !ownedOnly, { ownedOnly = false }, Modifier.weight(1f))
                        HudChip("Owned", ownedOnly, { ownedOnly = true }, Modifier.weight(1f), color = Hud.Cyan)
                    }
                }
            }
            if (decks.isEmpty()) {
                item {
                    HudPanel(title = "No decks yet") {
                        Text(
                            "Paste a Moxfield or Archidekt link, or a deck list in any text format. " +
                                "Every deck in the pool plays every other deck in simulated 4-player games.",
                            style = MaterialTheme.typography.bodyMedium, color = Hud.Muted,
                        )
                    }
                }
            }
            items(shown, key = { it.id }) { deck ->
                DeckRow(
                    deck = deck,
                    inPool = deck.id in settings.pool,
                    parentName = deck.parentId?.let { names[it] },
                    syncing = deck.id in syncing,
                    onToggle = { vm.togglePool(deck.id) },
                    onClick = { onOpen(deck.id) },
                )
            }
        }
    }
}

@Composable
private fun DeckRow(deck: Deck, inPool: Boolean, parentName: String?, syncing: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    HudPanel(accent = if (inPool) Hud.Cyan else Hud.Outline, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(deck.name, style = MaterialTheme.typography.titleMedium, color = Hud.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!deck.name.equals(deck.commanderLabel, ignoreCase = true)) {
                    Text(deck.commanderLabel, style = MaterialTheme.typography.bodySmall, color = Hud.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (deck.owned || deck.sourceUrl != null) {
                    Text(
                        listOfNotNull(if (deck.owned) "OWNED" else null, if (deck.sourceUrl != null) "LINKED" else null).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = Hud.Cyan,
                    )
                }
                if (parentName != null) {
                    Text("VARIANT OF ${parentName.uppercase()}", style = MaterialTheme.typography.labelSmall, color = Hud.Electric,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                ColorPips(deck.colorIdentity)
                Spacer(Modifier.height(4.dp))
                Text("${deck.cardCount} CARDS", style = MaterialTheme.typography.labelSmall,
                    color = if (deck.cardCount == 100) Hud.Muted else Hud.Warn)
                Text(
                    when {
                        syncing -> "SYNCING…"
                        deck.enriched -> "CARD DATA ✓"
                        else -> "OFFLINE"
                    },
                    style = MaterialTheme.typography.labelSmall, color = if (deck.enriched) Hud.Positive else Hud.Dim,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        HudChip(if (inPool) "✓ In pool" else "+ Add to pool", inPool, onToggle, color = Hud.Cyan)
    }
}
