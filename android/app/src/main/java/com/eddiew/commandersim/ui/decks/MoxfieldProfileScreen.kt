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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddiew.commandersim.AppViewModel
import com.eddiew.commandersim.data.DeckLinkImporter.ProfileDeck
import com.eddiew.commandersim.ui.components.ColorPips
import com.eddiew.commandersim.ui.components.HudButton
import com.eddiew.commandersim.ui.components.HudChip
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.NeonBar
import com.eddiew.commandersim.ui.components.Notice
import com.eddiew.commandersim.ui.components.ScreenHeader
import com.eddiew.commandersim.ui.theme.Hud
import kotlinx.coroutines.launch

/** Lists a Moxfield user's public decks with checkboxes and imports the checked ones in one go. */
@Composable
fun MoxfieldProfileScreen(vm: AppViewModel, initial: String?, onBack: () -> Unit, onDone: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val decks by vm.decks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf(initial ?: settings.moxfieldUser?.let { "https://moxfield.com/users/$it" } ?: "") }
    var user by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<ProfileDeck>?>(null) }
    var checked by remember { mutableStateOf(setOf<String>()) }
    var commanderOnly by rememberSaveable { mutableStateOf(true) }
    var owned by rememberSaveable { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Triple<Int, Int, String>?>(null) }
    var failures by remember { mutableStateOf<List<String>?>(null) }

    /** Moxfield deck ids already in the app (from their source links). */
    val imported = decks.mapNotNull { d -> d.sourceUrl?.substringAfter("moxfield.com/decks/", "")?.substringBefore('/')?.substringBefore('?')?.takeIf { it.isNotBlank() } }.toSet()

    fun load() {
        if (loading || input.isBlank()) return
        loading = true
        error = null
        failures = null
        scope.launch {
            try {
                val (u, found) = vm.moxfieldProfile(input)
                user = u
                list = found
                // New decks start checked; ones already imported start unchecked.
                checked = found.filter { (!commanderOnly || it.isCommander) && it.id !in imported }.map { it.id }.toSet()
            } catch (e: Exception) {
                error = e.message ?: "Couldn't load that profile."
            } finally {
                loading = false
            }
        }
    }

    val all = list.orEmpty()
    val shown = if (commanderOnly) all.filter { it.isCommander } else all
    val picked = shown.filter { it.id in checked }
    val running = progress != null && failures == null

    fun importPicked() {
        if (picked.isEmpty() || running) return
        failures = null
        progress = Triple(0, picked.size, "")
        scope.launch {
            failures = vm.importProfileDecks(picked, owned) { done, total, name -> progress = Triple(done, total, name) }
            checked = checked - picked.map { it.id }.toSet()
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader("Moxfield profile", user?.let { "@$it · ${all.size} public decks" } ?: "Import several decks at once")
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { HudChip("← Back", false, onBack, color = Hud.Muted) }
            item {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, singleLine = true,
                    label = { Text("Profile link or username") },
                    placeholder = { Text("https://moxfield.com/users/…", color = Hud.Dim) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { load() }),
                    modifier = Modifier.fillMaxWidth(), colors = hudFieldColors(),
                )
            }
            item { HudButton(if (loading) "Loading decks…" else "Load public decks", { load() }, Modifier.fillMaxWidth()) }
            error?.let { item { Notice(it, color = Hud.Negative) } }

            if (list != null) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudChip("Commander only", commanderOnly, { commanderOnly = !commanderOnly }, color = Hud.Cyan)
                        HudChip("Select all", false, { checked = checked + shown.map { it.id } })
                        HudChip("None", false, { checked = checked - shown.map { it.id }.toSet() }, color = Hud.Muted)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().clickable { owned = !owned }, verticalAlignment = Alignment.CenterVertically) {
                        Text("Mark imported decks as owned", style = MaterialTheme.typography.bodyMedium, color = Hud.Text, modifier = Modifier.weight(1f))
                        Switch(
                            checked = owned, onCheckedChange = { owned = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Hud.Bg, checkedTrackColor = Hud.Cyan, uncheckedTrackColor = Hud.Grid),
                        )
                    }
                }
                progress?.let { (done, total, name) ->
                    item {
                        HudPanel(title = if (running) "Importing $done / $total" else "Imported ${total - (failures?.size ?: 0)} of $total", accent = Hud.Cyan) {
                            NeonBar(if (total > 0) done.toFloat() / total else 1f, color = Hud.Cyan)
                            if (running && name.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(name, style = MaterialTheme.typography.bodySmall, color = Hud.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            failures?.let { f ->
                                f.forEach {
                                    Spacer(Modifier.height(4.dp))
                                    Text(it, style = MaterialTheme.typography.labelSmall, color = Hud.Warn)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("Card data and tags download in the background, one deck at a time.",
                                    style = MaterialTheme.typography.bodySmall, color = Hud.Muted)
                                Spacer(Modifier.height(8.dp))
                                HudButton("Go to decks", onDone, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                item {
                    HudButton(
                        when {
                            running -> "Importing…"
                            picked.isEmpty() -> "Check decks to import"
                            else -> "Import ${picked.size} deck${if (picked.size == 1) "" else "s"}"
                        },
                        { importPicked() }, Modifier.fillMaxWidth(),
                    )
                }
                if (shown.isEmpty()) {
                    item {
                        Text(
                            if (all.isEmpty()) "No public decks found. Private and unlisted decks aren't visible to other apps."
                            else "No Commander decks; turn off \"Commander only\" to see the other ${all.size}.",
                            style = MaterialTheme.typography.bodySmall, color = Hud.Dim,
                        )
                    }
                }
                items(shown, key = { it.id }) { d ->
                    ProfileDeckRow(d, d.id in checked, d.id in imported, enabled = !running) {
                        checked = if (d.id in checked) checked - d.id else checked + d.id
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileDeckRow(d: ProfileDeck, checked: Boolean, imported: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    HudPanel(accent = if (checked) Hud.Cyan else Hud.Outline, onClick = if (enabled) onToggle else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = checked, onCheckedChange = { if (enabled) onToggle() }, enabled = enabled,
                colors = CheckboxDefaults.colors(checkedColor = Hud.Cyan, uncheckedColor = Hud.Muted, checkmarkColor = Hud.Bg),
            )
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleSmall, color = Hud.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        d.format.replaceFirstChar { it.uppercase() }.ifBlank { null },
                        d.mainboardCount.takeIf { it > 0 }?.let { "$it cards" },
                        d.updated?.let { "updated $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = Hud.Dim,
                )
                if (imported) Text("ALREADY IMPORTED", style = MaterialTheme.typography.labelSmall, color = Hud.Positive, modifier = Modifier.padding(top = 2.dp))
            }
            ColorPips(d.colors)
        }
    }
}
