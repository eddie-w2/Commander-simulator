# v0.2.01: done

Delivered in v0.2.01 except where noted. Not done yet: turning off Forge's game log (it's wired into Forge's `Game` constructor) and a "Fast AI" profile (no obviously expensive switch in the AI profiles). Both are left for profiling.

# Original backlog

## 1. Forge pack: Forge updates without app updates
- **Scheduled GitHub job (daily):** builds Forge's latest `master` into one "Forge pack":
  - The engine (`forge-core`/`forge-game`/`forge-ai` and their libraries) plus our adapter (`ForgeEngine`, `ForgeRecorder`), compiled to Android bytecode with D8.
  - Card data: card scripts, editions, tokens, lists, AI profiles.
  - A manifest: Forge commit, date, size, minimum app version, SHA-256.
- **Publishing:** the pack goes to a fixed `forge-pack` release in our repo. If the adapter no longer compiles or a smoke game fails, nothing is published and the phone keeps the last good pack.
- **Code split:**
  - The app keeps only a small bridge interface: prepare decks → play game → `GameResult`.
  - Everything that touches Forge moves into the pack and is loaded at runtime with `DexClassLoader`.
  - The APK still bundles one pack as a fallback.
- **Checking:** the app checks for a new pack **at every launch**, and **asks before downloading** (shows size and Forge date).
- **Settings → Forge engine:**
  - Shows the installed pack (Forge commit and date).
  - Check now, download, and roll back to the previous pack (keep two on the phone).
  - Fall back to the bundled pack.
- **Unpacking:** once per pack version, removing older ones (already supported by the bundle `VERSION`).

## 2. Faster Forge simulation (currently several minutes per game)
Ordered by expected gain vs. effort:
1. **Release build instead of debug.** The APK is currently a *debuggable* build, and Android runs debuggable apps with reduced optimisation, which can make them several times slower. Ship a non-debuggable release variant, signed with the same key so updates still install over the old version. Keep R8/minify off at first, because Forge relies on reflection.
2. **Run games in parallel.** Phones have 6–8 cores, but Forge's global state (random generator, ID counters) isn't thread-safe. Use separate Android processes, 2–4 `:forge` worker services each with their own engine, instead of patching Forge.
3. **Load the card database once per app session**, not once per run: cache `StaticData` per card set.
4. **Cheaper recording:** the recorder calls `game.findById` (which searches every zone) for many events. Cache card id → name, and skip events we don't use (`CardStatsChanged` fires thousands of times a game).
5. **Turn off Forge's game log text** and other UI-only work (view updates, translated log strings) in headless games.
6. **Lighter AI settings:** profile the AI's per-decision cost and offer a "Fast AI" profile that turns off the most expensive look-ahead options.
7. **Shorter games:** lower the round cap (25 → ~18), and when a game hits the cap, score it by board state and life instead of counting a plain draw. Flag those games as adjudicated, which the stats already support.
8. **Measure:** log time per game, time per round and time spent in the AI, so each change above can be compared.

## 3. Check first
- **AI casting fix:** confirm on the phone that Forge's AI now casts spells. The Damage tab should show non-zero damage. The fix was loading `TypeLists.txt`, verified in source but not in a game yet.
- **Stuck runs:** if a run sticks at "Game 1", copy the run log from the Simulate screen.

## Version policy
Pushes to `main` bump 0.1.xx automatically and publish a release. This update moves to **0.2.01**, as agreed.
