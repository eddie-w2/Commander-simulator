package com.eddiew.commandersim.forgepack

import com.eddiew.commandersim.sim.BridgeDeck
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
import java.util.function.Supplier
import java.util.zip.ZipFile

/**
 * Runs real Commander games with Forge's rules engine and AI, without any Forge UI.
 *
 * [resRoot] holds the unpacked forge-res bundle (see tools/package_forge_res.py):
 * cardsfolder.zip, cardindex.tsv, editions/, tokenscripts/, blockdata/, ai/, languages/, lists/.
 * Only the scripts for cards in the simulated decks are extracted and loaded. The card database is kept between
 * runs and only rebuilt when new cards are needed.
 */
internal class ForgeEngine(
    private val resRoot: File,
    private val workDir: File,
) {
    /** Cards in the decks that Forge doesn't have a script for. Filled by [prepare]. */
    var missingCards: List<String> = emptyList()
        private set

    /** What the last game's recorder saw; useful when stats look wrong. */
    var lastDiagnostics: String = ""
        private set

    private val index: Map<String, String> by lazy {
        File(resRoot, "cardindex.tsv").readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
        }.toMap()
    }

    private val forgeDecks = HashMap<String, PreparedDeck>()
    /** Card names (lowercase) whose scripts are in the current card database. */
    private val loadedNames = HashSet<String>()
    private var dbLoaded = false

    /** A deck converted to Forge, with Forge card name → the app's card name. */
    internal class PreparedDeck(val deck: BridgeDeck, val forgeDeck: forge.deck.Deck, val nameMap: Map<String, String>)

    private fun entryFor(name: String): String? =
        index[name.lowercase()] ?: index[name.substringBefore(" // ").trim().lowercase()]

    fun prepare(decks: List<BridgeDeck>, log: (String) -> Unit) {
        log("Starting the Forge engine…")
        initOnce()
        val names = decks.flatMap { d -> d.commanders + d.cards.map { it.first } }.distinct()
        val missing = mutableListOf<String>()
        val newNames = names.filter { it.lowercase() !in loadedNames }
        if (!dbLoaded || newNames.isNotEmpty()) {
            // 1. Extract the card scripts (every card used so far this session, so the database can be reused).
            val cardsDir = File(workDir, "cardsfolder").apply { mkdirs() }
            ZipFile(File(resRoot, "cardsfolder.zip")).use { zip ->
                for (name in names) {
                    val entry = entryFor(name) ?: continue
                    val out = File(cardsDir, entry)
                    if (out.exists()) continue
                    out.parentFile?.mkdirs()
                    val ze = zip.getEntry(entry) ?: continue
                    zip.getInputStream(ze).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            }
            log("Loading card scripts, editions and tokens…")
            // 2. Load them (plus editions and tokens) into Forge's card database.
            val customEditions = File(workDir, "custom-editions").apply { mkdirs() }
            StaticData(
                CardStorageReader(cardsDir.path, null, false),
                CardStorageReader(File(resRoot, "tokenscripts").path, null, false),
                null, null,
                File(resRoot, "editions").path, customEditions.path, File(resRoot, "blockdata").path, "",
                "Latest Art All Editions",
                false, false, false, false,
            ).apply { setMulliganRule(forge.MulliganDefs.MulliganRule.London) }
            loadedNames += names.map { it.lowercase() }
            dbLoaded = true
        } else {
            log("Card database already loaded")
        }
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
            d.commanders.forEach { c -> resolve(c)?.let { fd.getOrCreate(DeckSection.Commander).add(it, 1) } }
            d.cards.forEach { (c, q) -> resolve(c)?.let { fd.main.add(it, q) } }
            forgeDecks[d.id] = PreparedDeck(d, fd, nameMap)
        }
        missingCards = missing.distinct().sorted()
        if (missingCards.isNotEmpty()) log("Forge doesn't know: ${missingCards.take(8).joinToString(", ")}")
    }

    fun play(seatDeckIds: List<String>, seed: Long, maxRounds: Int, maxMillis: Long, status: (String) -> Unit): GameResult {
        val prepared = seatDeckIds.map { forgeDecks[it] ?: error("Deck $it wasn't prepared") }
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
        val gameStart = System.currentTimeMillis()
        status("shuffling up")
        val recorder = ForgeRecorder(game, prepared.map { it.nameMap }, maxRounds, maxMillis) { round ->
            val secs = (System.currentTimeMillis() - gameStart) / 1000
            status("round $round · ${secs / 60}:%02d".format(secs % 60))
        }
        game.subscribeToEvents(recorder)

        // Forge recurses deeply; give the game its own thread with a large stack.
        var failure: Throwable? = null
        val t = Thread(null, {
            try {
                match.startGame(game)
            } catch (e: Throwable) {
                failure = e
            }
        }, "forge-game", 64L * 1024 * 1024)
        t.isDaemon = true
        t.start()
        t.join(maxMillis + 30_000)
        if (t.isAlive) {
            // The AI is stuck inside a turn: end the game and give it a moment to unwind.
            runCatching { game.setGameOver(GameEndReason.Draw) }
            t.join(10_000)
        }
        failure?.let { if (it is VirtualMachineError) throw it }
        lastDiagnostics = "events=${recorder.eventCounts.toSortedMap()} errors=${recorder.errors} failure=" +
            (failure?.let { "${it.javaClass.simpleName}: ${it.message} @ ${it.stackTrace.take(3).joinToString(" < ")}" } ?: "none")
        return recorder.finish(stopped = t.isAlive || failure != null)
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
            // Skips some expensive rules checks, as Forge's own "performance mode" preference does.
            runCatching { forge.game.spellability.Spell.setPerformanceMode(true) }

            // Forge's game data lists. TypeLists.txt is essential: it tells the engine that "Plains" etc. are
            // basic land types, which is where basic lands get their mana abilities.
            val lists = File(resRoot, "lists")
            fun lines(name: String): List<String> = File(lists, name).takeIf { it.exists() }?.readLines() ?: emptyList()
            if (!forge.card.CardType.Constant.LOADED.isSet) {
                val sections = forge.util.FileSection.parseSections(lines("TypeLists.txt"))
                for ((section, values) in sections) forge.card.CardType.Helper.parseTypes(section, values)
                forge.card.CardType.Constant.LOADED.set()
            }
            if (forge.game.card.CardUtil.NON_STACKING_LIST.isEmpty()) {
                lines("NonStackingKWList.txt").filter { it.length > 1 }.forEach { forge.game.card.CardUtil.NON_STACKING_LIST.add(it) }
            }
            forge.ai.simulation.AiCardLists.configure(
                Supplier { lines("ai-card-roles.txt") },
                Supplier { lines("commander-bracket-combos.txt") },
                Supplier { lines("ai-commander-combos.txt") },
            )
            initialised = resRoot.path
        }
    }
}
