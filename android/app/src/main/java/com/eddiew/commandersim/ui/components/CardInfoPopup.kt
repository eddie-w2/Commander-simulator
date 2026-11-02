package com.eddiew.commandersim.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.eddiew.commandersim.data.CardInfo
import com.eddiew.commandersim.ui.theme.Hud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/** Shows the card popup for a card name. Provided once at the app root. */
val LocalCardInfo = compositionLocalOf<(String) -> Unit> { {} }

/**
 * Tap does [onClick] (if any); long-press opens the card's Scryfall details.
 * Use on any row or label that shows a card name.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.cardPress(name: String, onClick: (() -> Unit)? = null): Modifier = composed {
    val show = LocalCardInfo.current
    combinedClickable(onClick = { onClick?.invoke() }, onLongClick = { show(name) })
}

/** Strips "Card · Deck" or "Card|deckId" labels down to the card name. */
fun cardNameOf(label: String): String = label.substringBefore(" · ").substringBefore("|").trim()

@Composable
fun CardInfoDialog(name: String, load: suspend (String) -> CardInfo, onDismiss: () -> Unit) {
    var info by remember(name) { mutableStateOf<CardInfo?>(null) }
    var error by remember(name) { mutableStateOf<String?>(null) }
    var images by remember(name) { mutableStateOf<Map<Int, Bitmap>>(emptyMap()) }
    val uri = LocalUriHandler.current
    LaunchedEffect(name) {
        try {
            val i = load(name)
            info = i
            i.faces.forEachIndexed { idx, f ->
                val url = f.imageUrl ?: return@forEachIndexed
                val bmp = withContext(Dispatchers.IO) {
                    runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
                }
                if (bmp != null) images = images + (idx to bmp)
            }
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load this card."
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Hud.SurfaceHi,
        title = { Text(info?.name ?: name, color = Hud.Text, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                val i = info
                when {
                    error != null -> Text(error!!, color = Hud.Negative)
                    i == null -> Text("Loading from Scryfall…", color = Hud.Muted)
                    else -> {
                        i.faces.forEachIndexed { idx, f ->
                            images[idx]?.let { bmp ->
                                Image(
                                    bmp.asImageBitmap(), contentDescription = f.name,
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            if (i.faces.size > 1) Text(f.name, style = MaterialTheme.typography.titleSmall, color = Hud.Cyan)
                            Row {
                                Text(f.typeLine.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Hud.Ice, modifier = Modifier.weight(1f))
                                f.manaCost?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Hud.Electric) }
                            }
                            f.oracleText?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(6.dp))
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = Hud.Text)
                            }
                            f.flavorText?.let {
                                Spacer(Modifier.height(6.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = Hud.Muted, fontStyle = FontStyle.Italic)
                            }
                            val stats = listOfNotNull(
                                if (f.power != null) "${f.power}/${f.toughness}" else null,
                                f.loyalty?.let { "Loyalty $it" },
                            )
                            if (stats.isNotEmpty()) Text(stats.joinToString(" · "), style = MaterialTheme.typography.labelLarge, color = Hud.Cyan,
                                modifier = Modifier.padding(top = 4.dp))
                            Spacer(Modifier.height(10.dp))
                        }
                        Detail("Mana value", i.manaValue?.let { num(it, 0) })
                        Detail("Colour identity", i.colorIdentity.ifEmpty { "Colourless" })
                        Detail("Keywords", i.keywords.joinToString(", ").ifEmpty { null })
                        Detail("Printing", listOfNotNull(i.setName, i.setCode?.let { "($it)" }, i.rarity).joinToString(" ").ifEmpty { null })
                        Detail("Released", i.releasedAt)
                        Detail("Artist", i.artist)
                        Detail("EDHREC rank", i.edhrecRank?.let { "#$it" })
                        Detail("Price", listOfNotNull(i.priceUsd?.let { "$$it" }, i.priceUsdFoil?.let { "$$it foil" }).joinToString(" · ").ifEmpty { null })
                        Detail("Legal", i.legalities.filter { it.second == "legal" }.joinToString(", ") { it.first }.ifEmpty { null })
                        Detail("Banned", i.legalities.filter { it.second == "banned" }.joinToString(", ") { it.first }.ifEmpty { null })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE", color = Hud.Cyan) } },
        dismissButton = {
            info?.scryfallUrl?.let { url -> TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text("SCRYFALL", color = Hud.Muted) } }
        },
    )
}

@Composable
private fun Detail(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Hud.Muted, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = Hud.Text, modifier = Modifier.weight(0.6f))
    }
}
