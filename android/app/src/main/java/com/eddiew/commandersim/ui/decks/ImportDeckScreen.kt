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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.eddiew.commandersim.data.DeckParser
import com.eddiew.commandersim.ui.components.HudButton
import com.eddiew.commandersim.ui.components.HudOutlineButton
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.Notice
import com.eddiew.commandersim.ui.components.ScreenHeader
import com.eddiew.commandersim.ui.components.TileRow
import com.eddiew.commandersim.ui.theme.Hud
import com.eddiew.commandersim.ui.theme.Mono

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
fun ImportDeckScreen(vm: AppViewModel, onDone: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var filter by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val parsed = remember(text) { DeckParser.parse(text) }
    val needsPick = parsed.commanders.isEmpty() && parsed.cards.isNotEmpty()

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        ScreenHeader("Import deck", "Paste a list from Moxfield, Archidekt, Arena or plain text")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Deck name (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), colors = hudFieldColors(),
            )
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("Deck list") },
                placeholder = { Text("1 Atraxa, Praetors' Voice *CMDR*\n1 Sol Ring\n1 Arcane Signet\n…", color = Hud.Dim) },
                textStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp, color = Hud.Text),
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 320.dp),
                colors = hudFieldColors(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudOutlineButton("Paste from clipboard", { clipboard.getText()?.text?.let { text = it } })
                if (text.isNotEmpty()) HudOutlineButton("Clear", { text = ""; picked = emptySet() }, color = Hud.Muted)
            }

            if (text.isNotBlank()) {
                HudPanel(title = "Parse preview") {
                    TileRow(
                        Triple("Cards", "${parsed.total}", if (parsed.total == 100) "valid size" else "expect 100"),
                        Triple("Unique", "${parsed.cards.size + parsed.commanders.size}", null),
                        Triple("Commander", if (parsed.commanders.isEmpty()) "${picked.size}" else "${parsed.commanders.size}", null),
                    )
                    Spacer(Modifier.height(10.dp))
                    parsed.commanders.forEach {
                        Text("◆ ${it.name}", style = MaterialTheme.typography.bodyMedium, color = Hud.Cyan)
                    }
                    parsed.warnings.filterNot { needsPick && it.startsWith("No commander") }.forEach {
                        Spacer(Modifier.height(6.dp))
                        Notice(it)
                    }
                    if (parsed.ignored.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Skipped ${parsed.ignored.size} lines (sideboard / maybeboard / unreadable)",
                            style = MaterialTheme.typography.bodySmall, color = Hud.Dim,
                        )
                    }
                }
            }

            if (needsPick) {
                HudPanel(title = "Pick commander (1 or 2 for partners)", accent = Hud.Warn) {
                    OutlinedTextField(
                        value = filter, onValueChange = { filter = it }, singleLine = true,
                        label = { Text("Search cards") }, modifier = Modifier.fillMaxWidth(), colors = hudFieldColors(),
                    )
                    Spacer(Modifier.height(8.dp))
                    val options = parsed.cards
                        .filter { it.name.lowercase() !in DeckParser.basicLands }
                        .filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }
                        .take(12)
                    options.forEach { c ->
                        val on = c.name in picked
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                picked = if (on) picked - c.name else if (picked.size < 2) picked + c.name else picked
                            }.padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (on) "◆" else "◇", color = if (on) Hud.Cyan else Hud.Dim)
                            Spacer(Modifier.padding(start = 8.dp))
                            Text(c.name, style = MaterialTheme.typography.bodyMedium, color = if (on) Hud.Cyan else Hud.Text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudOutlineButton("Cancel", onDone, color = Hud.Muted)
                HudButton(
                    "Save deck",
                    onClick = {
                        vm.saveDeck(name, parsed, picked.toList())
                        onDone()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = parsed.cards.isNotEmpty() && (!needsPick || picked.isNotEmpty()),
                )
            }
            Text(
                "Tip: mark the commander with *CMDR*, a [Commander] tag, or put it under a \"Commander\" header. " +
                    "Card types are looked up on Scryfall after saving.",
                style = MaterialTheme.typography.bodySmall, color = Hud.Dim, modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}
