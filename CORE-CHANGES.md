# Changes to the tamaemu core

Part of [TamaEmu for Android](https://github.com/mameise/TamaEmu_Android).

The emulator core in `app/src/main/cpp/core/` comes from
https://github.com/maragotchi/tamaemu (state 08b252b) and is used under the
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

Nothing else is modified. `main.c` and `panel.c` are not part of this port at
all — they are the SDL desktop front end, replaced here by
`app/src/main/cpp/native-emu.c`.
