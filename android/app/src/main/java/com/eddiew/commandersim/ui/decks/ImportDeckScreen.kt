package com.eddiew.commandersim.ui.decks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddiew.commandersim.AppViewModel
import com.eddiew.commandersim.ImportPreview
import com.eddiew.commandersim.data.DeckLinkImporter
import com.eddiew.commandersim.data.DeckParser
import com.eddiew.commandersim.ui.components.HudButton
import com.eddiew.commandersim.ui.components.HudOutlineButton
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.Notice
import com.eddiew.commandersim.ui.components.ScreenHeader
import com.eddiew.commandersim.ui.components.TileRow
import com.eddiew.commandersim.ui.components.cardPress
import com.eddiew.commandersim.ui.theme.Hud
import com.eddiew.commandersim.ui.theme.Mono
import kotlinx.coroutines.delay

@Composable
fun hudFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Hud.Electric,
    unfocusedBorderColor = Hud.Outline,
    focusedLabelColor = Hud.Electric,
    unfocusedLabelColor = Hud.Muted,
    cursorColor = Hud.Cyan,
    focusedTextColor = Hud.Text,
    unfocusedTextColor = Hud.Text,
    focusedContainerColor = Hud.Surface,
    unfocusedContainerColor = Hud.Surface,
)

@Composable
fun ImportDeckScreen(vm: AppViewModel, onDone: () -> Unit, onProfile: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var owned by rememberSaveable { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var filter by remember { mutableStateOf("") }
    var resolved by remember { mutableStateOf<ImportPreview?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var legendary by remember { mutableStateOf<List<String>>(emptyList()) }
    val clipboard = LocalClipboardManager.current

    val isLink = DeckLinkImporter.isDeckLink(text)
    val isProfile = DeckLinkImporter.moxfieldProfileUser(text) != null
    val local = remember(text) { if (isLink || isProfile || text.isBlank()) null else vm.previewText(text) }
    val preview = resolved ?: local

    // Links are fetched, and lists without a marked commander are checked against Scryfall,
    // shortly after the text stops changing.
    LaunchedEffect(text) {
        resolved = null
        error = null
        picked = emptySet()
        legendary = emptyList()
        if (text.isBlank() || isProfile) return@LaunchedEffect
        val needsWork = isLink || (local != null && local.commanders.isEmpty() && local.cards.isNotEmpty())
        if (!needsWork) return@LaunchedEffect
        delay(if (isLink) 100 else 700)
        busy = if (isLink) "Fetching deck…" else "Detecting commander…"
        try {
            val r = vm.resolveImport(text)
            resolved = r
            if (r.commanders.isEmpty() && r.cards.isNotEmpty()) legendary = vm.commanderOptions(r.cards)
            if (name.isBlank() && isLink) name = r.name
        } catch (e: Exception) {
            error = e.message ?: "Couldn't read that deck."
        } finally {
            busy = null
        }
    }

    val needsPick = preview != null && preview.commanders.isEmpty() && preview.cards.isNotEmpty() && busy == null

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        ScreenHeader("Import deck", "Paste a Moxfield or Archidekt link, or the deck list itself")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Deck name (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), colors = hudFieldColors(),
            )
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("Link or deck list") },
                placeholder = { Text("https://moxfield.com/decks/…\n\nor\n\n1 Lathril, Blade of the Elves\n\n1 Sol Ring\n1 Arcane Signet\n…", color = Hud.Dim) },
                textStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp, color = Hud.Text),
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                colors = hudFieldColors(),
            )
            Row(
                Modifier.fillMaxWidth().clickable { owned = !owned }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("I own this deck", style = MaterialTheme.typography.bodyMedium, color = Hud.Text)
                    Text("Owned decks appear in the Collection tab.", style = MaterialTheme.typography.bodySmall, color = Hud.Muted)
                }
                Switch(
                    checked = owned, onCheckedChange = { owned = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Hud.Bg, checkedTrackColor = Hud.Cyan, uncheckedTrackColor = Hud.Grid),
                )
            }
            if (isProfile) {
                Notice("That's a Moxfield profile. Import several of its decks at once:", color = Hud.Electric)
                HudButton("Choose decks from this profile", { onProfile(text.trim()) }, Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudOutlineButton("Paste", { clipboard.getText()?.text?.let { text = it } })
                if (text.isNotEmpty()) HudOutlineButton("Clear", { text = ""; name = "" }, color = Hud.Muted)
            }

            busy?.let { Notice(it, color = Hud.Electric) }
            error?.let { Notice(it, color = Hud.Negative) }

            if (preview != null && busy == null) {
                HudPanel(title = "Preview") {
                    val commanderCount = if (preview.commanders.isEmpty()) picked.size else preview.commanders.size
                    TileRow(
                        Triple("Cards", "${preview.total}", if (preview.total == 100) "valid size" else "expect 100"),
                        Triple("Unique", "${preview.cards.size + preview.commanders.size}", null),
                        Triple("Commander", "$commanderCount", null),
                    )
                    Spacer(Modifier.height(10.dp))
                    preview.commanders.forEach {
                        Text("◆ ${it.name}", style = MaterialTheme.typography.bodyMedium, color = Hud.Cyan)
                    }
                    preview.commanderNote?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
                    }
                    preview.warnings.filterNot { it.startsWith("No commander") }.forEach {
                        Spacer(Modifier.height(6.dp))
                        Notice(it)
                    }
                    if (preview.ignored > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text("Skipped ${preview.ignored} lines (sideboard / maybeboard / unreadable)",
                            style = MaterialTheme.typography.bodySmall, color = Hud.Dim)
                    }
                }
            }

            if (needsPick && preview != null) {
                HudPanel(title = "Which card is the commander?", accent = Hud.Warn) {
                    Text(
                        if (legendary.isNotEmpty()) "More than one legendary card fits this deck's colours. Pick 1, or 2 for partners."
                        else "Couldn't tell automatically. Pick 1, or 2 for partners.",
                        style = MaterialTheme.typography.bodySmall, color = Hud.Muted,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = filter, onValueChange = { filter = it }, singleLine = true,
                        label = { Text("Search cards") }, modifier = Modifier.fillMaxWidth(), colors = hudFieldColors(),
                    )
                    Spacer(Modifier.height(8.dp))
                    val pool = preview.cards.filter { it.name.lowercase() !in DeckParser.basicLands }
                    val ordered = pool.sortedByDescending { it.name in legendary }
                    ordered.filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }.take(12).forEach { c ->
                        val on = c.name in picked
                        Row(
                            Modifier.fillMaxWidth().cardPress(c.name) {
                                picked = if (on) picked - c.name else if (picked.size < 2) picked + c.name else picked
                            }.padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (on) "◆" else "◇", color = if (on) Hud.Cyan else Hud.Dim)
                            Spacer(Modifier.width(8.dp))
                            Text(c.name, style = MaterialTheme.typography.bodyMedium, color = if (on) Hud.Cyan else Hud.Text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (c.name in legendary) Text("LEGENDARY", style = MaterialTheme.typography.labelSmall, color = Hud.Electric)
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudOutlineButton("Cancel", onDone, color = Hud.Muted)
                HudButton(
                    "Save deck",
                    onClick = {
                        preview?.let { vm.saveDeck(name, it, picked.toList(), owned) }
                        onDone()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = preview != null && busy == null && preview.cards.isNotEmpty() && (!needsPick || picked.isNotEmpty()),
                )
            }
            Text(
                "The commander is found automatically: a card listed on its own (99+1 or 98+2), a Commander section, " +
                    "or the only legendary card that covers the deck's colours. Card types come from Scryfall.",
                style = MaterialTheme.typography.bodySmall, color = Hud.Dim, modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}
