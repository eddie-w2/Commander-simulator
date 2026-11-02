# Commander Simulator

Finds which Commander deck from a set of candidates wins most often against a known playgroup, by running large numbers of headless [Forge](https://github.com/Card-Forge/forge) AI games.

- [PLAN.md](PLAN.md): architecture, optimization plan and milestones
- [docs/STATS.md](docs/STATS.md): every statistic recorded, and where in Forge it comes from

## Android app (`/android`)

Kotlin + Jetpack Compose, dark grey / electric blue HUD theme.

- **Moxfield profile import:** enter a profile link (`moxfield.com/users/…`) to list that user's public decks, check the ones you want and import them all at once.
- **Precons:** every official Commander precon (from [MTGJSON](https://mtgjson.com)), synced and cached on the phone, added in one tap.
- **Decks:** paste a Moxfield/Archidekt link or a deck list in any text format. The commander is detected automatically (a card listed on its own for 99+1 / 98+2, a Commander section, or the only legendary card covering the deck's colours). Card data comes from Scryfall.
- **Simulate:** *Deck pool* mode runs every selected deck against the others in balanced 4-player pods. *A/B test* mode compares two versions of a deck (make one with **Create variant** on a deck) on identical games.
- **Results:** ranking with 95% confidence intervals, an all-vs-all matchup heatmap, and an A/B verdict with a paired confidence interval and side-by-side metrics. Each deck has a full report. **Every stat tile opens its own detail screen**, and every card opens its own breakdown.
- **Collection:** for decks tagged as owned: shared cards, deck overlap, and color breakdowns per deck and across the collection.
- **Settings (⚙ on Decks):** backup & restore to a file, and sync all decks imported from links. Every sync or restore keeps the previous list under the deck's **Versions**.

See [CHANGELOG.md](CHANGELOG.md) for what changed in each version.

### Simulation engines
- **Forge engine (default for real results):** Forge's own rules engine and AI run on the phone, with no Forge UI, in 1–4 worker processes at once. Only the scripts for cards in your decks are loaded. Every game event is recorded into the same statistics as the dashboards. You can stop early and keep the results.
- **Forge engine packs:** Forge isn't compiled into the app. It ships as a pack (`android/forgepack`: Forge's engine + our adapter + card data) that the app loads at runtime. One pack, built from [`forge.version`](forge.version), is bundled in the APK. The [Forge pack workflow](.github/workflows/forge-pack.yml) builds Forge's latest master daily, runs a smoke game, and publishes to the `forge-pack` release. The app offers it at launch. Shared types live in `android/engine-api`; changing them means bumping `ENGINE_API_VERSION`.
- **Quick model:** a fast model (`sim/QuickGame.kt`, `sim/CardModel.kt`) built on each card's real Scryfall data: mana cost and pips, the colours each land/rock/dork makes, enters-tapped lands, fetches, and roles (ramp, removal, wipes, counters, draw, tutors, burn/drain, tokens) from Scryfall's functional tags and rules text. Mana is paid pip by pip; play is simplified (no stack or combos). Thousands of games in seconds, so it's the tool for mana base, colours and curve (Mana tab); its win rates are rough.

Forge's engine jars and card data are built by [`tools/build-forge.sh`](tools/build-forge.sh) (needs git, JDK 17, Maven, Python 3) and are not checked in. CI builds and caches them.

### Install on your phone
- **Releases:** open the repo's **Releases** page and download `commander-sim-vX.Y.ZZ.apk` from the latest release.
- **Latest build:** **Actions** → latest green **Android** run → **Artifacts** → `commander-sim-debug-apk` (a zip).

Open the APK on your phone and allow "install unknown apps" when asked. From v0.1.02 on, every build is signed with the same key (`android/app/debug.keystore`), so new versions install over the old one and keep your decks. Builds before v0.1.02 used a random key, so uninstall those once first.

### Build locally
1. Run `tools/build-forge.sh` once (and again whenever `forge.version` changes).
2. Android Studio (Ladybug or newer), JDK 17: open `/android` and run. Or run `./gradlew assembleDebug` from `/android`.

### Updates
**Settings → App updates** checks this repo's GitHub Releases and installs newer versions in place. Android asks once to allow the app to install updates.

## License
Private while in testing. GPL-3.0 will be applied at the first working release. This is required because the simulator links Forge, which is GPL-3.0.
