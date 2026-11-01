# Commander Simulator

Finds which Commander deck from a set of candidates wins most often against a known playgroup, by running large numbers of headless [Forge](https://github.com/Card-Forge/forge) AI games.

- [PLAN.md](PLAN.md): architecture, optimization plan and milestones
- [docs/STATS.md](docs/STATS.md): every statistic recorded, and where in Forge it comes from

## Android app (`/android`)

Kotlin + Jetpack Compose, dark grey / electric blue HUD theme.

- **Decks:** paste a list (Moxfield, Archidekt, Arena or plain text). Card types and mana values are looked up on Scryfall. Mark each deck as a *candidate* (a deck you're choosing between) or an *opponent* (your playgroup).
- **Simulate:** set the games per candidate and seat rotation, then run.
- **Results:** recommended deck, win-rate ranking with 95% confidence intervals, and a matchup heatmap. Each deck has a report with Overview, Damage, Cards, Removal, Resources, and Combat & Board tabs.

> Until the Forge simulator server exists, the app uses a **simplified on-device sample model** (`sim/ToyGame.kt`). It produces every statistic so the dashboards can be built and tested, but its win rates are not meaningful. The results screen labels this.

### Install on your phone
1. Open the repo on GitHub → **Actions** → the latest green **Android** run.
2. Under **Artifacts**, download `commander-sim-debug-apk` and unzip it.
3. Open `app-debug.apk` on your phone and allow "install unknown apps" when asked.

### Build locally
Android Studio (Ladybug or newer), JDK 17: open `/android` and run. Or run `./gradlew assembleDebug` from `/android`.

### Screenshots
CI renders the main screens with Robolectric and pushes them to the [`screenshots`](../../tree/screenshots) branch.

## License
Private while in testing. GPL-3.0 will be applied at the first working release. This is required because the simulator links Forge, which is GPL-3.0.
