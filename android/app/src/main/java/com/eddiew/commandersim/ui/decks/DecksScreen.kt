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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddiew.commandersim.AppViewModel
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.DeckRole
import com.eddiew.commandersim.ui.components.ColorPips
import com.eddiew.commandersim.ui.components.HudButton
import com.eddiew.commandersim.ui.components.HudChip
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.ScreenHeader
import com.eddiew.commandersim.ui.theme.Hud

@Composable
fun DecksScreen(vm: AppViewModel, onImport: () -> Unit, onOpen: (String) -> Unit) {
    val decks by vm.decks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Deck Bay",
            "${decks.size} decks loaded · ${settings.roles.count { it.value == DeckRole.CANDIDATE }} candidates · " +
                "${settings.roles.count { it.value == DeckRole.OPPONENT }} opponents",
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                HudButton("+ Import deck", onImport, Modifier.fillMaxWidth())
            }
            if (decks.isEmpty()) {
                item {
                    HudPanel(title = "No decks yet") {
                        Text(
                            "Paste a deck list from Moxfield, Archidekt or any text export. " +
                                "Mark the decks you're choosing between as CANDIDATES and your playgroup's decks as OPPONENTS, " +
                                "then run a simulation.",
                            style = MaterialTheme.typography.bodyMedium, color = Hud.Muted,
                        )
                    }
                }
            }
            items(decks, key = { it.id }) { deck ->
                DeckRow(
                    deck = deck,
                    role = settings.roles[deck.id],
                    syncing = deck.id in syncing,
                    onRole = { vm.setRole(deck.id, it) },
                    onClick = { onOpen(deck.id) },
                )
            }
        }
    }
}

@Composable
private fun DeckRow(deck: Deck, role: DeckRole?, syncing: Boolean, onRole: (DeckRole?) -> Unit, onClick: () -> Unit) {
    val accent = when (role) {
        DeckRole.CANDIDATE -> Hud.Cyan
        DeckRole.OPPONENT -> Hud.Electric
        null -> Hud.Outline
    }
    HudPanel(accent = accent, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(deck.name, style = MaterialTheme.typography.titleMedium, color = Hud.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(deck.commanderLabel, style = MaterialTheme.typography.bodySmall, color = Hud.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                ColorPips(deck.colorIdentity)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${deck.cardCount} CARDS",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (deck.cardCount == 100) Hud.Muted else Hud.Warn,
                )
                Text(
                    when {
                        syncing -> "SYNCING…"
                        deck.enriched -> "SCRYFALL ✓"
                        else -> "OFFLINE"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (deck.enriched) Hud.Positive else Hud.Dim,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudChip("Candidate", role == DeckRole.CANDIDATE, { onRole(if (role == DeckRole.CANDIDATE) null else DeckRole.CANDIDATE) }, color = Hud.Cyan)
            HudChip("Opponent", role == DeckRole.OPPONENT, { onRole(if (role == DeckRole.OPPONENT) null else DeckRole.OPPONENT) })
        }
    }
}
