# PocketIV

Bare-bones live IV checker for Pokémon GO.

A floating button sits over the game and shows the IV% of whatever Pokémon is on screen, colored by appraisal tier, with a gold flip and a haptic fanfare on a 100%. Hold the button and a Calcy-style nickname lands on the clipboard, ready to paste into GO's rename box. It reads the screen with on-device text recognition (ML Kit through Play services) and a couple of pixel checks, and skips work whenever the screen hasn't changed, so it stays light on the battery. Nothing leaves the phone.

The math is the same as Calcy IV's: CP and HP narrowed by the stardust cost, the level arc and, when it's open, the team leader's appraisal bars. See [How it works](#how-it-works).

Not affiliated with or endorsed by Niantic, Nintendo, The Pokémon Company or the makers of Calcy IV. Use at your own risk.

## What it can do

- Show IV% live on a floating button (tap to pause and stop screen capture, hold to copy the nickname, drag to move)
- Pin the exact level from the arc, and the exact IVs from the appraisal bars
- Keep the result when you close the appraisal, and carry it over after a power-up or evolution
- Handle renamed Pokémon (from the appraisal text or the candy name) and Best Buddies
- Build nicknames from `$Tag$` schemes with a live preview, a tag list and presets, e.g. `$Perf/IV%$$MoveTypes$$Combs$` → `89㊋⑭⑭⑫`, or `!㊋⑮⑮⑮` on a perfect
- Keep a history of everything it scanned: species counts to find dupes or singles, and CSV export
- Read your trainer level from your GO profile (it asks when the levels look off)
- Launch Pokémon GO

## Requirements

- Android 15 or newer. Built and tuned on a Pixel 11 Pro running Android 17, the arc position is measured on that screen
- Google Play services (for on-device text recognition)
- Pokémon GO in English

## Install

1. Download `pocketiv-<version>.apk` from [Releases](../../releases) and open it on the phone (allow installs from your browser or file manager when Android asks).
2. Open **PocketIV**, tap **Start screen reading**, allow **Display over other apps**, then pick **Pokémon GO** (single app) in the screen sharing prompt.
3. Open your Trainer profile in GO once so PocketIV picks up your level, then open any Pokémon.

To check a download before installing, compare it against the release's `SHA256SUMS`. Release APKs are signed with this certificate (Android refuses updates signed with anything else):

```
SHA-256: 53:C9:1E:E3:29:7A:29:75:8C:60:DD:61:48:E4:25:9F:2C:55:CD:D3:D3:E2:42:64:FE:6E:62:61:DC:A7:0A:82
```

`apksigner verify --print-certs pocketiv-<version>.apk` shows the certificate of a downloaded APK.

## Build it yourself

Needs JDK 21 and the Android SDK with `platforms;android-37.0`.

1. `./gradlew testDebugUnitTest assembleDebug`
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk`

Release APKs are built and signed by GitHub Actions when a version tag is pushed. A debug build can't install over a release build or the other way around, since the signing keys differ: uninstall first (that clears settings and history, export first).

`uv run tools/gen_species.py` regenerates the base stats and move data from the [PvPoke](https://github.com/pvpoke/pvpoke) game master.

## How it works

- **CP and HP**: every level and IV combo is tried, `CP = max(10, floor((A+a)·√((D+d)·(S+s))·cpm²/10))`, `HP = max(10, floor((S+s)·cpm))`.
- **Stardust**: the power-up cost covers two levels (shadow, purified and lucky prices included).
- **Arc**: the dot angle is linear in CP multiplier; the arc ends at trainer level + 10.5.
- **Appraisal bars**: filled/total pixels × 15 per stat.
- **Memory**: each frame votes for the combos it allows, so one bad frame (a wing over the dot) gets outvoted.

## Things to know

- **No shiny detection.** GO doesn't mark shinies on the detail screen, only background sparkles, and those can't be told apart from other effects reliably. Search `shiny` in GO's storage instead.
- **Weak Pokémon stay wide.** At very low levels hundreds of combos give the same CP and HP; the appraisal settles it.
- **History can't see transfers.** Clear it and scroll your storage again to start fresh. Two of the same species with identical CP and HP count as one.
- **Move symbols need the detail screen.** Moves are read there; the appraisal covers them.
- **The arc rule is verified at trainer level 41.** If readings drift after leveling up, open your profile so it re-reads the level.

## License

[GNU AGPL v3.0](LICENSE). Copyright (C) 2026 Brandon Doster.
