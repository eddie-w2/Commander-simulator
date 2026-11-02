# Commander Simulator

Finds which Commander deck from a set of candidates wins most often against a known playgroup, by running large numbers of headless [Forge](https://github.com/Card-Forge/forge) AI games.

- [PLAN.md](PLAN.md): architecture, optimization plan and milestones
- [docs/STATS.md](docs/STATS.md): every statistic recorded, and where in Forge it comes from

## Android app (`/android`)

Kotlin + Jetpack Compose, dark grey / electric blue HUD theme.

- **Decks:** paste a Moxfield/Archidekt link or a deck list in any text format. The commander is detected automatically (a card listed on its own for 99+1 / 98+2, a Commander section, or the only legendary card covering the deck's colours). Card data comes from Scryfall.
- **Simulate:** *Deck pool* mode runs every selected deck against the others in balanced 4-player pods. *A/B test* mode compares two versions of a deck (make one with **Create variant** on a deck) on identical games.
- **Results:** ranking with 95% confidence intervals, an all-vs-all matchup heatmap, and an A/B verdict with paired confidence interval and side-by-side metrics. Each deck has a full report with Overview, Damage, Cards, Wins vs losses, Threats, Removal, Resources, and Combat & board tabs. Tap any card for its own breakdown.

> Until the Forge simulator server exists, the app uses a **simplified on-device sample model** (`sim/ToyGame.kt`). It produces every statistic so the dashboards can be built and tested, but its win rates are not meaningful. The results screen labels this.

### Install on your phone
1. Open the repo on GitHub → **Actions** → the latest green **Android** run.
2. Under **Artifacts**, download `commander-sim-debug-apk` and unzip it.
3. Open `app-debug.apk` on your phone and allow "install unknown apps" when asked.

### Build locally
Android Studio (Ladybug or newer), JDK 17: open `/android` and run. Or run `./gradlew assembleDebug` from `/android`.

## License
Private while in testing. GPL-3.0 will be applied at the first working release. This is required because the simulator links Forge, which is GPL-3.0.
