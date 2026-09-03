# Changes to the tamaemu core

Part of [TamaEmu for Android](https://github.com/mameise/TamaEmu_Android).

The emulator core in `app/src/main/cpp/core/` comes from
https://github.com/maragotchi/tamaemu (state 79e19b6) and is used under the
GPL-3.0. As required by the licence, every change made for this port is listed
here and marked in the source with a comment.

## `link.c`

1. **Added `#include <stdlib.h>` and `<time.h>`.**
   `clock_gettime` and `atoi` need them on Android/POSIX; the upstream build
   gets them implicitly elsewhere.

2. **Made the lead time adjustable at runtime**
   (`link_set_lead_us` / `link_get_lead_us`; `LINK_LEAD_US` is now only the
   starting value).
   The lead time is a trade-off: too little and network jitter corrupts bytes,
   too much and the firmware's own IR timeouts expire even though every byte
   arrives. Over Wi-Fi the right value depends on the connection, so it is a
   setting in the app rather than a compile-time constant.

## `emu.h`

3. **`LINK_LEAD_US` wrapped in `#ifndef`** so the build can supply a different
   starting value, and the two functions above declared.

## `periph.c`

4. **Settle pending cycles before the clock frequency changes** (`cmu_recalc`).
   `rtc_tick` converts cycles to seconds using the frequency in effect at the
   time of the call. When the firmware switches MCLK mid-interval, cycles that
   ran at 18.43 MHz get valued at 16 kHz — over a thousand times too much. The
   device sleeps and briefly wakes constantly, so the error accumulates.
   Measured before the change: the device clock ran 1.05x game time while awake
   and about 1.55x while allowed to sleep. After it: 1.00x while awake. The
   sleeping case still gains and is being reported upstream.

(The frame metering that used to cause a running clock during sleep was in this
port's own loop, not in the core — see `native-emu.c`.)

## `link.c` and `periph.c` held at an earlier state

4. **These two files are kept at the state before commit b0eefc8**
   ("make auto-link sessions resume safely"). Not because that change is wrong,
   but because visits between two phones were tested and work with this
   version. The newer one was tried and made no difference to the problem it
   was suspected of causing — the real cause was the lead time, which is now a
   setting. Moving to the newer files is fine, it just needs the two-phone test
   repeated.

Nothing else is modified. `main.c` and `panel.c` are not part of this port at
all — they are the SDL desktop front end, replaced here by
`app/src/main/cpp/native-emu.c`.
