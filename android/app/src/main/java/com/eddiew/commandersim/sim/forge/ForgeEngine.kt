package com.eddiew.commandersim.sim.forge

import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.sim.GamePlayer
import com.eddiew.commandersim.sim.GameResult
import forge.CardStorageReader
import forge.StaticData
import forge.ai.AiProfileUtil
import forge.ai.LobbyPlayerAi
import forge.deck.DeckSection
import forge.game.GameEndReason
import forge.game.GameRules
import forge.game.GameType
import forge.game.Match
import forge.game.player.RegisteredPlayer
import forge.item.PaperCard
import forge.util.Localizer
import forge.util.MyRandom
import java.io.File
import java.util.EnumSet
import java.util.Locale
import java.util.PropertyResourceBundle
import java.util.Random
import java.util.zip.ZipFile

/**
 * Runs real Commander games with Forge's rules engine and AI, without any Forge UI.
 *
 * [resRoot] holds the unpacked forge-res bundle (see tools/package_forge_res.py):
 * cardsfolder.zip, cardindex.tsv, editions/, tokenscripts/, blockdata/, ai/, languages/.
 * Only the scripts for cards in the simulated decks are extracted and loaded, which keeps start-up
 * fast and memory low on a phone.
 */
internal class ForgeEngine(
    private val resRoot: File,
    private val workDir: File,
    /** Stop a game as a draw after this many rounds per player. */
    private val maxRounds: Int = 25,
    /** Stop a game as a draw after this long. */
    private val maxMillisPerGame: Long = 5 * 60_000L,
) : GamePlayer {

    /** Cards in the decks that Forge doesn't have a script for. Filled by [prepare]. */
    var missingCards: List<String> = emptyList()
        private set

    private val index: Map<String, String> by lazy {
        File(resRoot, "cardindex.tsv").readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
        }.toMap()
    }

    private val forgeDecks = HashMap<String, PreparedDeck>()

    /** A deck converted to Forge, with Forge card name → the app's card name. */
    internal class PreparedDeck(val deck: Deck, val forgeDeck: forge.deck.Deck, val nameMap: Map<String, String>)

    private fun entryFor(name: String): String? =
        index[name.lowercase()] ?: index[name.substringBefore(" // ").trim().lowercase()]

    override fun prepare(decks: List<Deck>) {
        initOnce()
        // 1. Extract only the card scripts these decks use.
        val cardsDir = File(workDir, "cardsfolder").apply { deleteRecursively(); mkdirs() }
        val names = decks.flatMap { d -> (d.commanders + d.cards).map { it.name } }.distinct()
        val missing = mutableListOf<String>()
        ZipFile(File(resRoot, "cardsfolder.zip")).use { zip ->
            for (name in names) {
                val entry = entryFor(name)
                if (entry == null) { missing += name; continue }
                val out = File(cardsDir, entry)
                if (out.exists()) continue
                out.parentFile?.mkdirs()
                val ze = zip.getEntry(entry) ?: run { missing += name; null } ?: continue
                zip.getInputStream(ze).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        // 2. Load them (plus editions and tokens) into Forge's card database.
        val customEditions = File(workDir, "custom-editions").apply { mkdirs() }
        StaticData(
            CardStorageReader(cardsDir.path, null, false),
            CardStorageReader(File(resRoot, "tokenscripts").path, null, false),
            null, null,
            File(resRoot, "editions").path, customEditions.path, File(resRoot, "blockdata").path, "",
            "Latest Art All Editions",
            false, false, false, false,
        )
        // 3. Build Forge decks.
        forgeDecks.clear()
        for (d in decks) {
            val nameMap = HashMap<String, String>()
            val fd = forge.deck.Deck(d.name)
            fun resolve(name: String): PaperCard? {
                val db = StaticData.instance().commonCards
                val pc = db.getCard(name) ?: db.getCard(name.substringBefore(" // ").trim())
                if (pc == null) missing += name else nameMap[pc.name.lowercase()] = name
                return pc
            }
            d.commanders.forEach { c -> resolve(c.name)?.let { fd.getOrCreate(DeckSection.Commander).add(it, 1) } }
            d.cards.forEach { c -> resolve(c.name)?.let { fd.main.add(it, c.quantity) } }
            forgeDecks[d.id] = PreparedDeck(d, fd, nameMap)
        }
        missingCards = missing.distinct().sorted()
    }

    override fun play(seats: List<Deck>, seed: Long): GameResult {
        val prepared = seats.map { forgeDecks[it.id] ?: error("Deck ${it.name} wasn't prepared") }
        MyRandom.setRandom(Random(seed))
        val rules = GameRules(GameType.Commander).apply {
            setAppliedVariants(EnumSet.of(GameType.Commander))
            setGamesPerMatch(1)
        }
        val players = prepared.mapIndexed { i, p ->
            RegisteredPlayer.forCommander(p.forgeDeck).apply {
                setPlayer(LobbyPlayerAi("${i + 1}: ${p.deck.name}", null).apply { setAiProfile("Default") })
            }
        }
        val match = Match(rules, players, "Commander Sim")
        val game = match.createGame()
        game.setNoGUIUser()
        val recorder = ForgeRecorder(game, prepared.map { it.nameMap }, maxRounds, maxMillisPerGame)
        game.subscribeToEvents(recorder)

        // Forge recurses deeply; give the game its own thread with a large stack.
        var failure: Throwable? = null
        val t = Thread(null, {
            try {
                match.startGame(game)
            } catch (e: Throwable) {
                failure = e
            }
        }, "forge-game", 256L * 1024 * 1024)
        t.isDaemon = true
        t.start()
        t.join(maxMillisPerGame + 30_000)
        if (t.isAlive) {
            // The AI is stuck inside a turn: force a draw and give it a moment to unwind.
            runCatching { game.setGameOver(GameEndReason.Draw) }
            t.join(10_000)
        }
        failure?.let { if (it is VirtualMachineError) throw it }
        return recorder.finish(timedOut = t.isAlive || failure != null)
    }

    companion object {
        @Volatile private var initialised: String? = null

        /** Localizer and AI profiles are process-wide in Forge; set them up once per resource folder. */
        @Synchronized
        private fun ForgeEngine.initOnce() {
            if (initialised == resRoot.path) return
            val langDir = File(resRoot, "languages")
            forge.util.Lang.createInstance("en-US")
            runCatching { Localizer.getInstance().initialize("en-US", langDir.path + File.separator) }
            // Forge reads its strings through a URLClassLoader, which isn't dependable on Android.
            // Load the English file directly and put it in place so every lookup works.
            val bundle = File(langDir, "en-US.properties").inputStream().use { PropertyResourceBundle(it.reader(Charsets.UTF_8)) }
            val loc = Localizer.getInstance()
            fun set(field: String, value: Any?) = runCatching {
                Localizer::class.java.getDeclaredField(field).apply { isAccessible = true }.set(loc, value)
            }
            set("englishBundle", bundle)
            set("resourceBundle", bundle)
            set("locale", Locale.US)
            set("currentLanguageRegionID", "en-US")
            set("english", true)
            set("silent", true)
            AiProfileUtil.loadAllProfiles(File(resRoot, "ai").path)
            initialised = resRoot.path
        }
    }
}
