package com.eddiew.commandersim.ui.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddiew.commandersim.AppViewModel
import com.eddiew.commandersim.ui.components.HudButton
import com.eddiew.commandersim.ui.components.HudChip
import com.eddiew.commandersim.ui.components.HudOutlineButton
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.NeonBar
import com.eddiew.commandersim.ui.components.Notice
import com.eddiew.commandersim.ui.components.ScreenHeader
import com.eddiew.commandersim.ui.components.TileRow
import com.eddiew.commandersim.ui.theme.Hud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val decks by vm.decks.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError
    var pendingRestore by remember { mutableStateOf<String?>(null) }
    var syncResults by remember { mutableStateOf<List<String>>(emptyList()) }
    val version = remember { appVersion(context) }
    val available by vm.update.collectAsStateWithLifecycle()
    var checking by remember { mutableStateOf(false) }
    var checkedUpToDate by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var downloaded by remember { mutableStateOf<java.io.File?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = try {
                val json = vm.backupJson(version)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
                        ?: error("Couldn't open the file.")
                }
                "Backup saved: ${decks.size} decks, ${history.values.sumOf { it.size }} saved versions." to false
            } catch (e: Exception) {
                (e.message ?: "Backup failed.") to true
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                pendingRestore = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: error("Couldn't open the file.")
                }
            } catch (e: Exception) {
                message = (e.message ?: "Couldn't read the file.") to true
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Settings", "Commander Sim v$version")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HudChip("← Back", false, onBack, color = Hud.Muted)

            message?.let { (text, isError) -> Notice(text, color = if (isError) Hud.Negative else Hud.Positive) }

            HudPanel(title = "App updates", accent = if (available != null) Hud.Cyan else Hud.Electric) {
                Text("Installed: v$version", style = MaterialTheme.typography.bodyMedium, color = Hud.Text)
                val rel = available
                if (rel != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("Available: v${rel.version}", style = MaterialTheme.typography.titleMedium, color = Hud.Cyan)
                    if (rel.notes.isNotBlank()) {
                        Text(rel.notes.take(600), style = MaterialTheme.typography.bodySmall, color = Hud.Muted)
                    }
                    Spacer(Modifier.height(8.dp))
                    val p = downloadProgress
                    when {
                        downloaded != null -> HudButton("Install v${rel.version}", {
                            if (vm.updater.canInstall()) vm.updater.install(downloaded!!) else vm.updater.openInstallPermissionSettings()
                        }, Modifier.fillMaxWidth())
                        p != null -> {
                            NeonBar(p)
                            Text("Downloading… ${(p * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = Hud.Muted)
                        }
                        else -> HudButton("Download & install", {
                            updateError = null
                            downloadProgress = 0f
                            scope.launch {
                                try {
                                    val f = vm.updater.download(rel) { downloadProgress = it }
                                    downloaded = f
                                    if (vm.updater.canInstall()) vm.updater.install(f) else vm.updater.openInstallPermissionSettings()
                                } catch (e: Exception) {
                                    updateError = e.message ?: "Download failed."
                                } finally {
                                    downloadProgress = null
                                }
                            }
                        }, Modifier.fillMaxWidth())
                    }
                    if (!vm.updater.canInstall()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Android will ask you once to allow Commander Sim to install updates. Turn it on, then tap Install again.",
                            style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    HudOutlineButton(if (checking) "Checking…" else "Check for updates", {
                        if (!checking) {
                            checking = true
                            updateError = null
                            scope.launch {
                                try {
                                    checkedUpToDate = vm.checkForUpdate() == null
                                } catch (e: Exception) {
                                    updateError = "Couldn't reach GitHub: ${e.message}"
                                } finally {
                                    checking = false
                                }
                            }
                        }
                    }, Modifier.fillMaxWidth())
                    if (checkedUpToDate) {
                        Spacer(Modifier.height(6.dp))
                        Text("You're on the latest version.", style = MaterialTheme.typography.bodySmall, color = Hud.Positive)
                    }
                }
                updateError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Hud.Negative)
                }
                Spacer(Modifier.height(6.dp))
                Text("Updates come from this app's GitHub Releases and keep your decks and results.",
                    style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
            }

            HudPanel(title = "Backup & restore") {
                TileRow(
                    Triple("Decks", "${decks.size}", "${decks.count { it.owned }} owned"),
                    Triple("Versions", "${history.values.sumOf { it.size }}", "saved"),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "A backup is one file with every deck, owned tags, saved versions, simulation settings and the latest results. " +
                        "Save it to your Drive or Downloads.",
                    style = MaterialTheme.typography.bodySmall, color = Hud.Muted,
                )
                Spacer(Modifier.height(10.dp))
                HudButton("Create backup file", {
                    val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
                    exportLauncher.launch("commander-sim-backup_$stamp.json")
                }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                HudOutlineButton("Restore from backup…", { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, Modifier.fillMaxWidth())
            }

            HudPanel(title = "Linked decks") {
                val linked = decks.count { it.sourceUrl != null }
                Text("$linked decks were imported from a link. Syncing pulls the latest list; the old list is saved as a version.",
                    style = MaterialTheme.typography.bodySmall, color = Hud.Muted)
                Spacer(Modifier.height(8.dp))
                HudButton(
                    if (syncing.isNotEmpty()) "Syncing…" else "Sync all linked decks",
                    { scope.launch { syncResults = vm.syncAllLinked().map { it.message } } },
                    Modifier.fillMaxWidth(), enabled = linked > 0 && syncing.isEmpty(),
                )
                syncResults.forEach {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Hud.Ice)
                }
            }

            HudPanel(title = "About") {
                Text("Version $version", style = MaterialTheme.typography.bodyMedium, color = Hud.Text)
                Text("Simulation engine: on-device sample model (Forge server coming).", style = MaterialTheme.typography.bodySmall, color = Hud.Muted)
                Text("Card data from Scryfall.", style = MaterialTheme.typography.bodySmall, color = Hud.Muted)
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    pendingRestore?.let { json ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            containerColor = Hud.SurfaceHi,
            title = { Text("Restore backup", color = Hud.Text) },
            text = {
                Text(
                    "MERGE keeps the decks on this phone and adds or updates the ones in the backup (your current copies go into Versions).\n\n" +
                        "REPLACE makes the backup your whole library. Decks only on this phone are removed.",
                    color = Hud.Muted,
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        pendingRestore = null
                        scope.launch { message = runCatching { vm.restoreBackup(json, replace = true) to false }.getOrElse { (it.message ?: "Restore failed.") to true } }
                    }) { Text("REPLACE", color = Hud.Negative) }
                    TextButton(onClick = {
                        pendingRestore = null
                        scope.launch { message = runCatching { vm.restoreBackup(json, replace = false) to false }.getOrElse { (it.message ?: "Restore failed.") to true } }
                    }) { Text("MERGE", color = Hud.Cyan) }
                }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("CANCEL", color = Hud.Muted) } },
        )
    }
}
