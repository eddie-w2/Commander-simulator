# Changelog

## v0.3.06
- **Fix: tokens.** Tokens were played as copies of the card that made them, so a commander that makes tokens (Merry, Warden of Isengard) was credited with every token's damage. That damage also counted as commander damage. Now:
  - Each token is its own creature with its real size and keywords (e.g. 1/1 Soldier with lifelink).
  - Token damage shows as its own source, e.g. "Soldier token (Merry, Warden of Isengard)".
  - Tokens never deal commander damage.
  - Treasure, Food and Clue tokens give mana, life and cards instead of attacking.
- **The quick model now reads card text into abilities**, once per run, so games stay fast:
  - Triggers: enters, dies, attacks, upkeep, end step, landfall, casting spells, artifacts or creatures entering, creatures dying, and the "whenever an opponent casts" draw engines.
  - Effects: tokens, draw, drain, burn, life gain and +1/+1 counters.
  - Activated abilities ("{2}, {T}: create…") use mana left over after combat.
  - "Once each turn" limits are respected.
- **Combat:**
  - Attackers tap and can't block on opponents' turns (vigilance excepted).
  - New keywords: first strike, vigilance, defender, "can't block", indestructible, hexproof and ward.
  - Tribal lords pump only their creature type; anthems stop when they leave.
  - Attacks go where the most damage gets through, finishing low-life players when lethal is on.
  - Blocks are chosen to survive, trade up, or stop lethal.
- **Interaction:**
  - Instant removal and counterspells are held up and used on opponents' turns.
  - Protection spells save a targeted creature.
  - Exile, destroy, bounce and damage-based removal behave differently.
  - Board wipes respect indestructible.
  - Recursion returns the best permanent from the graveyard.
- **Coverage report:** the deck screen shows which cards the quick model fully reads, partly reads or ignores, and exactly which lines. The run log reports each deck's coverage.

## v0.3.05
- **Runs keep going in the background.** A simulation now runs in a foreground service (with a progress notification and a wake lock), so switching apps or turning the screen off no longer stops it. Leaving the app's screen doesn't stop it either.
- **Nothing is lost if a run dies.**
  - Forge runs save every finished game as they go.
  - If the app is killed or the engine keeps failing, the finished games become a partial result in the run history.
  - Simulate → **Resume** plays only the games that are left, with the same decks and schedule, so the finished run matches an uninterrupted one.
  - Stopping early also leaves the run resumable.
- **Run history:** every run is saved. Results has a drop-down of past runs, newest first. Each run shows its engine, games and decks, and is marked if partial. Runs can be deleted.
- **Deck test record:** a deck's screen shows how it has done across all saved runs:
  - win rate with its margin of error, games, wins and average place
  - how it compares to an average deck at that table size
  - results by engine and a win-rate-by-run trend
  - win rate when each opponent was at the table
  - the list of runs, each opening that run's deck report
- **Pool:** All / None buttons on the pool. Decks imported from a Moxfield profile are no longer added to the pool.
- **Startup checks:** the app checks for an app update and a new Forge engine every time it opens, and offers to install either one (app update first).

## v0.3.04
- **Card categories:** every deck's cards are sorted automatically into ramp, card draw, removal, board wipes, counterspells, protection, tutors, recursion, token makers, burn/drain and lifegain. A card can fall into several. Categories come from Scryfall tags and rules text.
  - The deck screen has a category radar plus a count for each category, coloured against a common guideline (~10 ramp, ~10 draw, ~8 removal, ~3 wipes).
  - Tap a category to list its cards.
- **Collection:**
  - **Colour filter** (W U B R G C) at the top. Every chart follows it, so "G" shows the same analytics for green cards only. Pick several colours for cards that are all of them; C shows colourless cards.
  - **Mana curves:** a curve for the whole collection, average mana value by deck, and a small curve for each deck.
  - **Categories:** average per deck for each category, a category-by-deck chart with the guideline, and a decks × categories heatmap.
- **Simulation animation:** a retro scanner (hex data stream, oscilloscope trace, sweeping beam) plays while a simulation runs.
- Black colour identity is now dark purple so it no longer looks like colourless.
- Design direction written down in `docs/DESIGN.md`.

## v0.3.03
- Fix: Forge runs failed at start-up ("Missing placeholder face for 'eiganjo_dynastorian_replenish'") when a deck had a card that borrows its second half from another card, such as the new "prepare" cards (Eiganjo Dynastorian uses Replenish). The engine now loads those cards too.
- If Forge still can't load a card script, it skips that one card and lists it under "Forge doesn't know" instead of failing the whole run.

## v0.3.02
- **Moxfield profile import:** Decks → Moxfield profile (or paste a profile link into Import). Enter `moxfield.com/users/<name>` or a username to list that user's public decks with checkboxes.
  - Select all/none, plus a "Commander only" filter.
  - Decks you've already imported are marked and start unchecked.
  - Optionally mark the imported decks as owned.
  - Imports the checked decks one after another with a progress bar, and lists any that failed.
  - The app remembers the last profile.
- Card data downloads now run one deck at a time, so bulk imports don't overload Scryfall.

## v0.3.01
- **Quick model replaces the quick sample.** It now plays your real cards:
  - Real mana costs and coloured pips, paid from the sources in play. Each land, rock and dork makes its real colours. Tapped lands enter tapped, fetches find your colours, and land ramp puts lands into play.
  - Land drops pick the land that fixes the colours your hand needs.
  - Card roles come from Scryfall's functional tags plus rules text: ramp, removal, board wipes, counterspells, card draw and draw engines, tutors, burn/drain, tokens, lifegain, anthems and equipment.
  - Combat uses real power/toughness and flying, reach, trample, deathtouch, lifelink, haste, menace, double strike and unblockable.
  - It runs on several cores at once.
- **New Mana tab** in each deck report (quick model):
  - Colour availability by turn, per colour and all colours.
  - Mana sources by turn against a 1-land-per-turn line.
  - Colour screw, land screw and flood rates.
  - Commander castable on curve.
  - Cards most often stuck in hand for colours. Each card's detail screen also shows its "stuck for colours" rate.
- **Mana base analysis** on the deck screen and in the Mana tab:
  - Pips vs sources per colour, with rough source targets.
  - Land, rock, dork and ramp counts, plus tapped lands.
  - Card role counts.
  - Warnings when a colour is short.
- **Richer card data:** decks now store mana cost, produced mana, rules text, P/T, keywords and Scryfall tags. Existing decks update in the background after this update.

## v0.2.02
- Fix: the long-press card popup failed with HTTP 400 (Scryfall requires an Accept header).

## v0.2.01
- **Forge engine packs:** Forge now ships as a separate "engine pack" (Forge's engine + card data). A daily GitHub job builds Forge's latest code, and the pack is published only if a real smoke game passes. The app checks for a new pack at every launch and asks before downloading it. Settings → Forge engine shows the installed engine, can check now, and can switch back to an earlier or the built-in engine. App updates are no longer needed for Forge updates.
- **Faster Forge:**
  - The app is now a release (non-debuggable) build, which Android runs fully optimised.
  - Games run in parallel in separate worker processes (Simulate → Parallel games, 1–4). A crash or out-of-memory in one game no longer takes the app down.
  - Each worker keeps its card database between runs.
  - The recorder skips events it doesn't use.
  - Games are capped at 18 rounds, and unfinished games are scored on life and board instead of counted as draws. The results say how many were scored.
- **Card details:** long-press any card name (deck lists, card tables, report rows, shared cards, swaps) to see its Scryfall card: image, rules text, type, P/T, legality, price and more. The card detail screen also has a Card text button.
- **Expected-value lines on charts:**
  - Lands in play and mana available: 1 per round.
  - Cards drawn: a 1-per-round draw step, plus a new running-total chart.
  - Land drop rate: 100%.
  - Life charts: starting life of 40.

## v0.1.04
- Forge runs show live progress: the current game, round and time, updated every second, plus a run log (Show log) covering each start-up step.
- If a run fails, the error and log are shown and can be copied to share.
- Forge games run with Forge's performance mode and a safer game thread.

## v0.1.03
- **Forge engine on the phone:** simulations can now use Forge's real rules engine and AI. The app loads only the card scripts your decks need, records every game event into the full statistics, shows live progress with time remaining, and lets you stop early and keep the results. Cards Forge doesn't know are listed in the results.
- **Commander precons:** Decks → Precons lists every official Commander precon, synced from MTGJSON, kept on the phone and refreshed weekly. Search by name, set code or year, preview the commander and colours, and add a deck in one tap (optionally marked as owned). Added precons can be re-synced like linked decks.
- **Self-update:** Settings → App updates checks GitHub Releases, downloads the new APK and opens the installer. The Decks tab shows when an update is available.
- Seat statistics now use actual turn order when the engine picks who goes first.

## v0.1.02
- **Layered analytics:** every stat tile opens its own detail screen. Each detail screen has the value, its rank across all decks, a comparison bar for every deck, the wins-vs-losses gap, and related metrics. It also has breakdowns specific to that kind of stat:
  - **Damage:** which cards dealt it (total / combat / non-combat / kills), damage by round for the top cards, damage per opponent, and the card × opponent matrix.
  - **Damage taken:** who and what hurts you, final blows, and life by round.
  - **Removal:** your removal cards, what you remove, who you target, your cards that get removed and by what, and countered spells.
  - **Card flow:** draws, hand size, dead cards, cards held longest, and win rate when drawn vs. not.
  - **Mana:** lands, land-drop rate, mana spent, ramp timing, and mana value vs. round cast.
  - **Commander:** cast timing, win rate by cast round, damage, and what removes it.
  - **Combat & board**, plus **game length** for the whole run.
- **Owned decks:** tag a deck as owned on import or from the deck screen.
- **Collection tab:** for owned decks:
  - The most shared cards, and the extra copies you'd need to build every deck at once.
  - A deck-overlap matrix and the most similar deck pairs.
  - Color breakdowns per deck and across all owned decks (every copy or unique cards, lands on/off).
  - Commander identities and card types.
- **Backup & restore:** saves one JSON file with all decks, owned tags, versions, settings and the latest results. You can restore it by merging or replacing.
- **Link sync:** decks imported from Moxfield/Archidekt can be re-synced, one at a time or all at once. The previous list is saved as a version before every change, and any version can be restored.
- Settings screen; GitHub Releases now carry the APK.

## v0.1.01
- Deck pool replaces candidate/opponent; A/B variant testing on identical games.
- Moxfield/Archidekt link import; automatic commander detection (99+1 / 98+2).
- Granular stats: per-card damage, kill shots, nemesis cards, wins vs losses, card detail.

## v0.1.0
- First build: deck import, sample simulator, results dashboards.
