# TamaEmu for Android

An Android port of **[tamaemu](https://github.com/maragotchi/tamaemu)** by
maragotchi — an emulator for the colour Tamagotchi models built around the
Epson S1C33 processor: **iD L, iD, iD Melody, P's, 4U, 4U+, Plus Color and
Hexagontchi**.

Your Tama runs in the background and ages while the app is closed, sits on the
home screen as a widget with working buttons, plays its own sounds, and tells
you when it needs attention.

> **No firmware is included, and none is ever uploaded.**
> You need a ROM dump of a device you own. It is copied into the app's private
> folder and never leaves your phone.

## Features

- Full-screen emulator with A/B/C buttons
- Three home screen widgets with working buttons: compact, large, and a plain
  one without the egg
- Replace the egg with your own artwork — the app hands you a template showing
  where the screen and buttons sit
- Runs in the background as a foreground service
- Lost game time is caught up **without ever setting the clock forward**, so
  the firmware sees every second and no care event is skipped
- Sound rendered from the emulator core's cycle-stamped tone events
- Notification and vibration when your Tama calls for attention
- Downloadable content (games, items, wallpapers) including slot management
- Visits between two phones over Wi-Fi
- Save data export and import
- Gamepad support, with the buttons assigned by simply pressing them
- English and German, switchable inside the app

## Requirements

- **Android 8.0 (API 26) or newer**
- Built for **arm64-v8a, armeabi-v7a and x86_64**, so 32-bit devices and the
  Android Studio emulator work too
- The emulator runs an 18.4 MHz processor in real time. A phone from roughly
  2018 onwards manages that comfortably; on much older hardware the Tama may
  run slower than real life.

## Getting started

1. Install the APK.
2. Open the app, tap ⚙ and choose **Import firmware**. Pick your own ROM dump.
3. Confirm the device model — the app suggests one based on the dump's reset
   vector.
4. That's it. Add a widget from your launcher if you want one.

## Building

Requirements: JDK 17, Android SDK 34, NDK 26.1.10909125, CMake 3.22.1.

    ./gradlew assembleRelease

**Signing.** No keystore ships with this repository. Without one, the release
build is signed with the debug key — installable, but not the official build.
To use your own key, either put a `tama.keystore` into `app/` or point Gradle
at one:

    ./gradlew assembleRelease \
        -PtamaKeystore=/path/to/my.jks \
        -PtamaKeystorePassword=… -PtamaKeyAlias=… -PtamaKeyPassword=…

GitHub Actions builds on every push (`.github/workflows/build.yml`) and puts
the APK up as a build artifact. For a signed build, set four repository
secrets: `KEYSTORE_BASE64` (the keystore, base64 encoded), `KEYSTORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD`. Without them — or if the encoding is not
usable — the workflow still builds, just with the debug key, and says so in
the log.

Encoding the keystore on Windows, in **PowerShell** (not `cmd`, and not
`certutil`, which adds header lines):

    [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\tama.keystore")) `
        | Set-Content C:\temp\keystore.txt -NoNewline

The result is one long line, roughly 3600 characters for a 2.7 KB keystore.
Keep that file outside the repository.

## Licence and credits

**GNU General Public License version 3 or later** — see [LICENSE](LICENSE).

The emulator core in `app/src/main/cpp/core/` was written by
**[maragotchi](https://github.com/maragotchi/tamaemu)** and is used under the
same licence. Every change made to it for this port is listed in
[CORE-CHANGES.md](CORE-CHANGES.md) and marked in the source.

The DLC tooling in `app/src/main/cpp/dlc/` also comes from that project and is
unmodified.

This project is not affiliated with, endorsed by or connected to Bandai.
Tamagotchi is a trademark of its respective owner.
