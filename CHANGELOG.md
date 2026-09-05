# Changelog

## Unreleased (dev)

Everything below was developed and tested on the `dev` branch.

### Fixed — game clock ran fast

The Tamagotchi's own clock drifted, badly on the P's and noticeably on the
iD L, especially with "keep awake" turned off.

Two independent causes, both found by measurement:

- **Cycles were valued at the wrong clock rate.** When the firmware falls
  asleep it drops MCLK from 18.43 MHz to 16 kHz. Cycles that had already run at
  the fast rate were then converted to seconds using the slow one — over a
  thousand times too much. Pending cycles are now settled *before* the
  frequency changes (`cmu_recalc`, see CORE-CHANGES.md).
- **Frames carried more game time than they should.** A frame used to be a
  fixed number of cycles, computed once from the clock rate; and the core books
  time in steps of 256 cycles, which during sleep is 15.6 ms — almost a whole
  frame. A frame is now one sixtieth of an *emulated* second, the target is
  accumulated rather than re-derived (so any overshoot is taken back in the
  next frame), and the booking step is finer while the clock is slow.

Measured afterwards over long headless runs with both firmwares: game clock to
emulated time, factor 1.000; largest jump per frame 0.0179 s against a target
of 0.0167 s.

### Fixed — sound stopped after installing downloadable content

Three problems in one path:

- `AudioTrack.write` can fail by returning a negative value instead of
  throwing. That return value was ignored, so the loop kept writing into a dead
  output.
- When the audio thread did exit, its "running" flag stayed set, so it could
  never be restarted — the sound was gone until the process ended.
- The real trigger: the service's own timer kept running while the user
  interface was unloading the core, and reloaded it mid-operation. The audio
  producer then referred to a core that no longer existed.

The output is now rebuilt on error, the thread can restart, and all core work
from the user interface runs with the service timer suspended.

### Fixed — black screen after importing firmware

Importing left a window between "new firmware written" and "device profile
chosen" in which the service timer started the core with the *new* firmware and
the *old* profile. The core then stopped on an illegal instruction and stayed
that way. The core is now held unloaded across the whole import, and a core
that refuses to start is unloaded instead of left behind.

### Fixed — switching firmware left the core stuck and the sound late

Switching used to unload the core and *also* ask the service to reload it. Both
ran, so the core started twice in quick succession and could end up wedged.
The whole switch now happens under the same lock, and the service timer brings
the core back up afterwards — one path, not two.

The audio ring and its timing marks are static and survive an unload, so after
a switch the consumer played stale samples and the producer only recovered once
its own safety net kicked in. The audio pipeline is now reset explicitly
whenever a core starts.

### Added — several firmwares side by side

Firmware files are kept in a small library, each with its own device profile
and its own save data. Import as many as you like and switch between them
without re-importing; removing one takes its saves with it.

Save data is now tied to the firmware it belongs to (`<device>_<crc32>.sav`)
instead of just the device profile — a save *is* the flash image of its
firmware, so it only ever loads with the right one. Existing saves are renamed
into the new scheme, not discarded. As a safety net, a save whose reset vector
does not match the loaded firmware is refused with a message instead of
producing a black screen.

### Added — widgets

- A fourth widget: tall (2×3 cells), with the same egg drawing.
- A plain widget without the egg — just the screen and three buttons that scale
  with the widget.
- Your own artwork instead of the egg, for each egg variant. The app writes a
  template marking the egg outline, the screen and the three buttons exactly
  where it draws them, so a hand-drawn case fits.

### Fixed — buttons could be drawn black on black

The on-screen buttons used the system theme, and some manufacturer skins draw
them dark on a dark background. They are now drawn by the app itself, and the
colour is a setting (light, dark, teal, pink, blue, yellow) with the label
colour chosen automatically for contrast.

### Added — controls and display

- Gamepad support. Buttons are assigned by pressing them, not by looking up key
  codes.
- Speed buttons optionally on the main screen.
- The on-screen buttons can be hidden for gamepad-only use; a discreet gear
  appears so the settings stay reachable.
- Fullscreen, with a separate switch for sharp (integer) scaling versus filling
  the area. On a handheld the picture roughly doubles.

### Changed

- Emulator core updated to upstream 79e19b6, which fixes downloaded games that
  used to glitch or crash (the DSTRAM was modelled 2 KB instead of 4 KB).
- "Restart ROM" and "Restart emulator" merged into one "Start over".
- "Close app" now really stops everything: core unloaded, service stopped,
  notifications cleared, process ended — and it stays closed.
- Set the device clock to phone time (a deliberate jump, unlike catching up).
- Lint now fails the build on `NewApi` and missing translations.
- Builds for arm64, arm32 and x86_64 instead of arm64 only.
- Widget texts follow the language chosen in the app, not the system language.
