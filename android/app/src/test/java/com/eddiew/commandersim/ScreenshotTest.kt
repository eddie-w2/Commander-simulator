package com.eddiew.commandersim

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eddiew.commandersim.data.model.DeckRole
import com.eddiew.commandersim.data.model.SimConfig
import com.eddiew.commandersim.sim.SampleSimulator
import com.eddiew.commandersim.ui.components.LocalHudMotion
import com.eddiew.commandersim.ui.theme.CommanderSimTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the main screens to PNGs in app/build/screenshots so the look can be reviewed without a
 * device. Runs on the JVM with Robolectric's native graphics.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1500dp-xhdpi")
class ScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }

    private fun shot(name: String) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        val view = rule.activity.window.decorView.rootView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        FileOutputStream(File(outDir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun renderScreens() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = AppViewModel(app, autoLoad = false)
        val report = runBlocking {
            SampleSimulator(seed = 7).run(
                SimConfig(
                    candidateIds = PreviewDecks.candidates.map { it.id },
                    opponentIds = PreviewDecks.opponents.map { it.id },
                    gamesPerCandidate = 600,
                ),
                PreviewDecks.all,
            ) {}
        }
        val roles = PreviewDecks.candidates.associate { it.id to DeckRole.CANDIDATE } +
            PreviewDecks.opponents.associate { it.id to DeckRole.OPPONENT }
        runBlocking { vm.seedForPreview(PreviewDecks.all, roles, report) }

        rule.setContent { CompositionLocalProvider(LocalHudMotion provides false) { CommanderSimTheme { App(vm) } } }
        shot("01_decks")

        rule.onNodeWithText("SIMULATE").performClick()
        shot("02_simulate")

        rule.onNodeWithText("RESULTS").performClick()
        shot("03_results")

        val best = report.rankedCandidates.first().name
        rule.onAllNodesWithText(best).onFirst().performClick()
        shot("04_report_overview")
        for ((tab, file) in listOf(
            "DAMAGE" to "05_report_damage",
            "CARDS" to "06_report_cards",
            "REMOVAL" to "07_report_removal",
            "RESOURCES" to "08_report_resources",
            "COMBAT & BOARD" to "09_report_combat",
        )) {
            rule.onNodeWithText(tab).performClick()
            shot(file)
        }
    }

    @Test
    fun renderImportAndDeck() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = AppViewModel(app, autoLoad = false)
        runBlocking { vm.seedForPreview(PreviewDecks.all, mapOf(PreviewDecks.krenko.id to DeckRole.CANDIDATE), null) }
        rule.setContent { CompositionLocalProvider(LocalHudMotion provides false) { CommanderSimTheme { App(vm) } } }
        rule.onNodeWithText("Krenko Goblins").performClick()
        shot("10_deck_detail")
    }
}
