/* native-emu.c - JNI-Bruecke fuer tamaemu (S1C33 Farb-Tamagotchi) auf Android.
 *
 * Ersetzt src/main.c aus dem Originalprojekt: gleiche Init-Reihenfolge,
 * gleiche Deadline-Taktung (1/60 s), aber ohne SDL und ohne CLI.
 * Der Emulator laeuft in einem eigenen pthread, Java holt nur Bilder ab.
 */
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <time.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdarg.h>
#include "emu.h"
#include "dlc.h"

#define TAG "tamaemu"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define MIN_HOLD_MS 120   /* kuerzere Druecke schluckt die Firmware-Entprellung */
#define TAP_MS      160

static Emu        E;
static pthread_t  TH;
static pthread_mutex_t FBLOCK = PTHREAD_MUTEX_INITIALIZER;

static uint32_t   FB[PANEL_W * PANEL_H];
static volatile uint64_t FRAME_NO;
static volatile int  RUNNING, WANT_STOP, WANT_PERSIST, WANT_RESET;
static volatile int  LOADED;
static volatile int  ASLEEP;
static volatile uint8_t HELD;
static volatile int64_t HOLD_UNTIL[3];
static volatile int  SPEED = 1;
static volatile int  STAYAWAKE;
static char SAVPATH[600], RAMPATH[620], STATEPATH[620], BUILDID[32];
static char STATE_MSG[160] = "noch nicht geladen";
static volatile double MIPS;

/* Zeitausgleich: offene Spielzeit in Sekunden und der Faktor dafuer. */
static volatile double CATCH_DEF;
static volatile int    CATCH_MULT = 60;
/* Laufender Zeitversatz: was die Emulation gegenueber der Wanduhr verliert. */
static volatile double DRIFT_LOST;      /* insgesamt verlorene Sekunden */
static volatile double DRIFT_NOW;       /* aktueller Rueckstand */
static volatile double SAVE_LAST = -1;  /* emu_secs des letzten Flash-Schreibens */
static volatile unsigned SAVE_N;        /* wie oft geschrieben */
/* Besuch: Auto-Link folgt dem Verbindungsmodus der Firmware. */
static volatile int    LINK_ON, LINK_PORT = 7878;
/* Schliessen gehoert in den Emulator-Faden: der Kern arbeitet dort mit den
 * Steckdosen, und ein Schliessen aus dem Bedienfaden heraus zieht sie ihm
 * unter den Fuessen weg. */
static volatile int    LINK_WANT_CLOSE;
static void wlog(const char *fmt, ...);
static void aud_render(void);

#define CALLHOOK_N 4
static volatile uint32_t CALL_HOOK[CALLHOOK_N];
static volatile int      CALL_HOOK_N;
static volatile int      CALL_MELODY = 1;
static volatile int      CALLING;
static volatile int      CALL_EVENTS;
static double  MEL_T;
static int     MEL_STEP;
static double  HOOK_T = -1e9;      /* letzter Zaehlerablauf */
static double  CALL_T;             /* seit wann es ruft */
static double  CALL_EXPIRY = 300;  /* Sekunden, dann verfaellt der Ruf */
static char    CALL_WHY[48];

/*
 * Ausgeloest wird NUR von der Rufmelodie. Der Zaehlerablauf allein genuegt
 * nicht: im Laborlauf ueber eine Stunde lief ein Zaehler ab, ohne dass ein Ruf
 * hoerbar wurde (FINDINGS v13). Er dient jetzt als Bestaetigung - faellt er mit
 * der Melodie zusammen, ist der Ruf sicher.
 */
static void call_raise(const char *why)
{
    /* Beide Zaehler koennen in derselben Minute ablaufen - nicht doppelt melden. */
    if (CALLING && E.emu_secs - CALL_T < 2.0) return;
    CALL_EVENTS++;
    CALL_T = E.emu_secs;
    snprintf(CALL_WHY, sizeof CALL_WHY, "%s", why);
    if (!CALLING) {
        CALLING = 1;
        wlog("%8.1f  RUF erkannt (%s)\n", E.emu_secs, why);
        LOGI("[ruf] erkannt: %s", why);
    }
}


static void wlog(const char *fmt, ...)
{
    /* Nur noch ins Systemprotokoll - der eigene Ringpuffer war ein
     * Diagnosewerkzeug und ist raus. */
    char b[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(b, sizeof b, fmt, ap);
    va_end(ap);
    LOGI("%s", b);
}
/* Fakten ueber die geladene Datei, fuer die Fehlersuche. */
static int64_t  ROM_BYTES;   /* auch auf 32-Bit-Geraeten eindeutig */
static uint32_t RESET_VEC;
static uint32_t STOP_PC;

static int64_t now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static uint64_t now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

static uint8_t cur_mask(void)
{
    int64_t t = now_ms();
    uint8_t m = HELD;
    for (int i = 0; i < 3; i++) if (HOLD_UNTIL[i] > t) m |= (uint8_t)(1 << i);
    return m;
}

/* Flash-Abbild und A0RAM auf die Karte schreiben. Laeuft nur im Emu-Thread. */
static void persist_now(int force)
{
    if (!LOADED) return;
    if (SAVPATH[0] && (E.flash_dirty || force)) {
        FILE *f = fopen(SAVPATH, "wb");
        if (f) {
            fwrite(E.rom, 1, E.dev.rom_size, f);
            fclose(f);
            E.flash_dirty = false;
            SAVE_LAST = E.emu_secs;
            SAVE_N++;
            LOGI("[flash] %llu prog / %llu erase -> %s",
                 (unsigned long long)E.flash_programs,
                 (unsigned long long)E.flash_erases, SAVPATH);
        } else LOGE("[flash] kann %s nicht schreiben", SAVPATH);
    }
    /*
     * Vollstaendiger Zustand (state.c aus dem Upstream): sichert die GANZE
     * Emu-Struktur, also auch Zeitgeber, Uhrregister und Anzeigezustand -
     * alles, was unsere .ram-Datei nicht abdeckt. Er wird nach dem
     * Flash-Abbild geschrieben, damit die Pruefsumme darin zu dem passt, was
     * auf der Karte liegt.
     */
    if (STATEPATH[0] && !E.stopped) {
        char why[128] = "";
        if (!state_save(&E, STATEPATH, BUILDID, why, sizeof why))
            LOGE("[zustand] nicht gesichert: %s", why);
    }

    /* Batteriegepufferter Speicher. Das echte Geraet haelt ihn unter Strom;
     * hier muss er auf die Karte, sonst faellt das Tama nach einem Prozesstod
     * auf den letzten Flash-Stand der Firmware zurueck.
     * WICHTIG: nicht nur A0RAM - die laufenden Zustandsgroessen (z.B. die
     * Beduerfniszaehler beim iD L) liegen im DSTRAM. */
    if (RAMPATH[0]) {
        FILE *f = fopen(RAMPATH, "wb");
        if (f) {
            fwrite(E.a0ram,  1, A0RAM_MAX,  f);
            fwrite(E.ivram,  1, IVRAM_MAX,  f);
            fwrite(E.dstram, 1, DSTRAM_MAX, f);
            fclose(f);
        }
    }
}

static void *emu_thread(void *arg)
{
    (void)arg;
    uint64_t deadline = 0;
    int64_t next_persist = now_ms() + 10000;
    uint64_t mips_cyc = E.cycles;
    int64_t  mips_at = now_ms();
    double t4_last = 0, input_at = 0, catch_prev_secs = E.emu_secs;
    uint8_t tone_was = 0;

    RUNNING = 1;
    while (!WANT_STOP && !E.stopped) {
        if (WANT_RESET) {
            WANT_RESET = 0;
            cpu_reset(&E);
            LOGI("[cpu] Neustart, Reset-Vektor -> %08x", E.pc);
        }

        /*
         * Ein Bild dauert 1/60 EMULIERTE Sekunde - nicht eine feste Anzahl
         * Zyklen.
         *
         * Frueher wurde die Anzahl einmal je Bild aus der Taktfrequenz
         * bestimmt. Wechselt die Firmware MITTEN im Bild in den Schlaf, faellt
         * der Takt von 18,43 MHz auf 16 kHz - die restlichen Zyklen dieses
         * Bildes zaehlen dann als vielfache Sekunden. Aus einem Bild wurden so
         * bis zu 18 Sekunden Spielzeit, und die Uhr des Tamas lief davon.
         *
         * Die Obergrenze fuer die Zyklen bleibt als Notbremse, damit die
         * Schleife nicht haengt, falls emu_secs einmal stehen sollte.
         */
        double t_ziel = E.emu_secs + 1.0 / 60.0;
        double mclk = E.cmu.mclk_hz > 0 ? E.cmu.mclk_hz : E.dev.osc3_hz;
        uint64_t notbremse = E.cycles + (uint64_t)(mclk / 60.0) + 100000;

        while (E.emu_secs < t_ziel && E.cycles < notbremse && !E.stopped) {
            /* Ruf-Haken: greift VOR der Ausfuehrung, E.pc ist die Stelle,
             * die gleich drankommt. */
            if (CALL_HOOK_N) {
                for (int k = 0; k < CALL_HOOK_N; k++)
                    if (E.pc == CALL_HOOK[k]) { HOOK_T = E.emu_secs; break; }
            }
            cpu_step(&E);

            /* Wachhalten: Schlaf-Flag der Firmware wieder loeschen. Bei
             * beschleunigter Uhr auch auf Geraeten, die den Weckdruck fressen. */
            if ((E.stay_awake || (E.rtc_mult > 1 && E.dev.wake_press_lost)) &&
                E.dev.has_sleep_flag)
                E.a0ram[E.dev.sleep_flag - E.dev.a0ram_base] = 0;

            if (E.stopped && !STOP_PC) STOP_PC = E.pc;
            /* Flanke still -> Ton: hier wird die Rufmelodie erkannt. */
            if (E.tone_on && !tone_was && CALL_MELODY) {
                float f = E.tone_freq;
                /* 1396 Hz, dann 1174 Hz innerhalb einer halben Sekunde. */
                if (MEL_STEP == 0 && f > 1330.0f && f < 1460.0f) {
                    MEL_STEP = 1; MEL_T = E.emu_secs;
                } else if (MEL_STEP == 1 && E.emu_secs - MEL_T < 0.5 &&
                           f > 1120.0f && f < 1230.0f) {
                    MEL_STEP = 0;
                    call_raise(E.emu_secs - HOOK_T < 3.0
                               ? "Melodie und Zaehlerablauf" : "nur Melodie");
                } else if (MEL_STEP == 1 && E.emu_secs - MEL_T >= 0.5) {
                    MEL_STEP = 0;
                }
            }
            tone_was = E.tone_on;
            if (E.cycles - E.last_tick >= 256) {
                periph_tick(&E, (uint32_t)(E.cycles - E.last_tick));
                E.last_tick = E.cycles;
                periph_buttons(&E, cur_mask());
            }
        }

        /* Langes T4-Parken heisst Schlaf; kurze Pausen liegen zwischen Animationen. */
        uint32_t t4off = 0x3007A6u - E.dev.io_base;
        if (t4off < IORAM_MAX && (E.ioram[t4off] & 1)) t4_last = E.emu_secs;
        if (cur_mask()) input_at = E.emu_secs;
        ASLEEP = (!E.stay_awake && (E.emu_secs - t4_last > 30.0) &&
                  (E.emu_secs - input_at > 2.0)) ? 1 : 0;

        if (LINK_WANT_CLOSE) {
            LINK_WANT_CLOSE = 0;
            link_close(&E.auto_link_storage);
            E.link = NULL;
            wlog("%8.1f  BESUCH beendet\n", E.emu_secs);
        }

        aud_render();          /* Ton fuer genau die Zyklen dieses Bildes */

        pthread_mutex_lock(&FBLOCK);
        int w, h;
        lcd_render(&E, FB, &w, &h);
        FRAME_NO++;
        pthread_mutex_unlock(&FBLOCK);

        /* Zeitausgleich: die Spieluhr laeuft schneller, bis die Luecke zu ist.
         * Die Firmware zaehlt dabei JEDE Sekunde - es wird nichts uebersprungen. */
        if (CATCH_DEF > 0.0 && CATCH_MULT > 1) {
            /* Kleiner Rueckstand wird leise aufgeholt, grosser zuegig. */
            int m = CATCH_DEF < 120.0 ? 5 : CATCH_MULT;
            double d_emu = E.emu_secs - catch_prev_secs;
            if (d_emu > 0) CATCH_DEF -= d_emu * (m - 1);
            if (CATCH_DEF <= 0.0) { CATCH_DEF = 0.0; E.rtc_mult = SPEED; }
            else E.rtc_mult = m;
        }
        DRIFT_NOW = CATCH_DEF;
        catch_prev_secs = E.emu_secs;

        /* Ein Ruf, den niemand beantwortet, verfaellt von selbst - sonst steht
         * RUFT auch dann noch da, wenn das Tama laengst wieder ruhig ist. */
        if (CALLING && CALL_EXPIRY > 0 && E.emu_secs - CALL_T > CALL_EXPIRY) {
            CALLING = 0;
            wlog("%8.1f  RUF verfallen (unbeantwortet)\n", E.emu_secs);
        }

        int64_t t = now_ms();
        if (t - mips_at >= 1000) {
            MIPS = (double)(E.cycles - mips_cyc) / (double)(t - mips_at) / 1000.0;
            mips_cyc = E.cycles; mips_at = t;
        }
        if (WANT_PERSIST || t >= next_persist) {
            persist_now(WANT_PERSIST == 2);
            WANT_PERSIST = 0;
            next_persist = t + 10000;
        }

        /* Absolute Deadline: Verschlafen korrigiert sich selbst statt sich zu summieren. */
        uint64_t now = now_ns();
        if (!deadline) deadline = now;
        else if (now > deadline + 250000000ull) {
            /* Wir sind zurueckgefallen (Doze, Lastspitze, Bildschirm aus).
             * Frueher wurde die Zeit hier verworfen - genau daraus entsteht der
             * Drift. Jetzt wird sie als Rueckstand vermerkt und nachgeholt. */
            double lost = (double)(now - deadline) / 1e9;
            DRIFT_LOST += lost;
            CATCH_DEF += lost;
            deadline = now;
        }
        deadline += 16666667ull;
        if (now < deadline) {
            struct timespec ts;
            uint64_t d = deadline - now;
            ts.tv_sec = (time_t)(d / 1000000000ull);
            ts.tv_nsec = (long)(d % 1000000000ull);
            nanosleep(&ts, NULL);
        }
    }
    persist_now(0);
    RUNNING = 0;
    if (E.stopped) LOGE("[cpu] gestoppt: %s", E.stop_reason);
    return NULL;
}

/* ------------------------------------------------------------------ JNI */

JNIEXPORT jobjectArray JNICALL
Java_com_bernd_tamaemu_EmuNative_devices(JNIEnv *env, jclass c)
{
    const DeviceProfile *d;
    int n = 0;
    while ((d = device_at((size_t)n)) != NULL) n++;
    jobjectArray arr = (*env)->NewObjectArray(env, n,
        (*env)->FindClass(env, "java/lang/String"), NULL);
    for (int i = 0; i < n; i++) {
        d = device_at((size_t)i);
        char line[160];
        snprintf(line, sizeof line, "%s|%s", d->name, d->title);
        jstring s = (*env)->NewStringUTF(env, line);
        (*env)->SetObjectArrayElement(env, arr, i, s);
        (*env)->DeleteLocalRef(env, s);
    }
    return arr;
}

JNIEXPORT jstring JNICALL
Java_com_bernd_tamaemu_EmuNative_init(JNIEnv *env, jclass c, jstring jrom,
                                       jstring jdev, jstring jsav, jstring jram,
                                       jstring jstate, jstring jbuild)
{
    char msg[256] = "";
    const char *rom = (*env)->GetStringUTFChars(env, jrom, 0);
    const char *dev = (*env)->GetStringUTFChars(env, jdev, 0);
    const char *sav = (*env)->GetStringUTFChars(env, jsav, 0);
    const char *ram = (*env)->GetStringUTFChars(env, jram, 0);
    const char *st  = (*env)->GetStringUTFChars(env, jstate, 0);
    const char *bid = (*env)->GetStringUTFChars(env, jbuild, 0);

    const DeviceProfile *p = device_find(dev);
    if (!p) p = device_default();

    if (E.rom) { free(E.rom); E.rom = NULL; }
    memset(&E, 0, sizeof E);
    E.dev = *p;
    E.cmu.osc3_hz = p->osc3_hz;
    E.rtc_mult = SPEED;
    E.stay_awake = STAYAWAKE ? true : false;

    E.rom = calloc(1, E.dev.rom_size);
    if (!E.rom) { snprintf(msg, sizeof msg, "kein Speicher fuer %u Bytes ROM",
                           (unsigned)E.dev.rom_size); goto out; }

    FILE *f = fopen(rom, "rb");
    if (!f) { snprintf(msg, sizeof msg, "ROM nicht lesbar: %s", rom); goto out; }
    fseek(f, 0, SEEK_END);
    ROM_BYTES = (int64_t)ftell(f);
    fseek(f, 0, SEEK_SET);
    size_t n = fread(E.rom, 1, E.dev.rom_size, f);
    fclose(f);
    if (n < 0x1000) { snprintf(msg, sizeof msg, "ROM zu klein (%zu Bytes)", n); goto out; }
    LOGI("[rom] %zu Bytes, Geraet %s (%s)", n, E.dev.name, E.dev.title);

    snprintf(SAVPATH, sizeof SAVPATH, "%s", sav);
    snprintf(RAMPATH, sizeof RAMPATH, "%s", ram);
    snprintf(STATEPATH, sizeof STATEPATH, "%s", st);
    snprintf(BUILDID, sizeof BUILDID, "%s", bid);

    FILE *sf = fopen(SAVPATH, "rb");
    if (sf) {
        size_t sn = fread(E.rom, 1, E.dev.rom_size, sf);
        fclose(sf);
        LOGI("[flash] Spielstand geladen (%zu Bytes)", sn);
    }
    /*
     * Zwei Wege zurueck in den laufenden Betrieb, in dieser Reihenfolge:
     *
     * 1. Der vollstaendige Zustand (state.c aus dem Upstream). Er passt nur,
     *    wenn Kennung, Geraet und Pruefsumme des Flash-Abbilds stimmen - nach
     *    einem App-Update also nicht mehr, weil sich der Aufbau der Struktur
     *    geaendert haben kann.
     * 2. Sonst der Rueckfall: Reset und unsere drei RAM-Bereiche aus der
     *    .ram-Datei. Die ueberlebt Updates, deckt aber Zeitgeber und
     *    Uhrregister nicht ab.
     */
    E.auto_link = LINK_ON ? true : false;
    E.auto_link_port = LINK_PORT;

    int zustand_da = 0;
    if (STATEPATH[0]) {
        char why[128] = "";
        StateResult r = state_load(&E, STATEPATH, BUILDID, why, sizeof why);
        if (r == STATE_LOADED) {
            zustand_da = 1;
            snprintf(STATE_MSG, sizeof STATE_MSG, "vollstaendig geladen");
            LOGI("[zustand] vollstaendig geladen");
        } else if (r == STATE_NONE) {
            snprintf(STATE_MSG, sizeof STATE_MSG, "keiner vorhanden, Rueckfall auf .ram");
        } else {
            snprintf(STATE_MSG, sizeof STATE_MSG, "abgelehnt (%s), Rueckfall auf .ram", why);
            LOGE("[zustand] abgelehnt: %s", why);
        }
    }

    if (!zustand_da) {
        /* Alte Dateien enthalten nur A0RAM; die neuen zusaetzlich IVRAM und
         * DSTRAM. Beides wird gelesen, damit ein Wechsel nichts kostet. */
        FILE *rf = fopen(RAMPATH, "rb");
        if (rf) {
            size_t rn = fread(E.a0ram, 1, A0RAM_MAX, rf);
            size_t iv = fread(E.ivram, 1, IVRAM_MAX, rf);
            size_t ds = fread(E.dstram, 1, DSTRAM_MAX, rf);
            fclose(rf);
            LOGI("[ram] %zu + %zu + %zu Bytes wiederhergestellt", rn, iv, ds);
        }
        cpu_reset(&E);
    }
    /* Nach einem geladenen Zustand ist E.pc der Fortsetzungspunkt und NICHT
     * der Reset-Vektor - den lesen wir dann direkt aus der Vektortabelle. */
    if (zustand_da) RESET_VEC = mem_read32(&E, E.dev.ttbr_reset);
    else RESET_VEC = E.pc;
    STOP_PC = 0;
    LOGI("[cpu] Datei %lld Bytes, Reset-Vektor -> %08x",
         (long long)ROM_BYTES, RESET_VEC);
    LOADED = 1;

out:
    (*env)->ReleaseStringUTFChars(env, jrom, rom);
    (*env)->ReleaseStringUTFChars(env, jdev, dev);
    (*env)->ReleaseStringUTFChars(env, jsav, sav);
    (*env)->ReleaseStringUTFChars(env, jram, ram);
    (*env)->ReleaseStringUTFChars(env, jstate, st);
    (*env)->ReleaseStringUTFChars(env, jbuild, bid);
    return (*env)->NewStringUTF(env, msg);
}

JNIEXPORT jboolean JNICALL
Java_com_bernd_tamaemu_EmuNative_start(JNIEnv *env, jclass c)
{
    if (!LOADED || RUNNING) return (jboolean)RUNNING;
    WANT_STOP = 0;
    if (pthread_create(&TH, NULL, emu_thread, NULL) != 0) return JNI_FALSE;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_bernd_tamaemu_EmuNative_stop(JNIEnv *env, jclass c)
{
    if (!RUNNING) return;
    WANT_STOP = 1;
    pthread_join(TH, NULL);
}

JNIEXPORT void JNICALL
Java_com_bernd_tamaemu_EmuNative_unload(JNIEnv *env, jclass c)
{
    if (RUNNING) { WANT_STOP = 1; pthread_join(TH, NULL); }
    if (LOADED) persist_now(0);
    LOADED = 0;
    if (E.rom) { free(E.rom); E.rom = NULL; }
    FRAME_NO = 0;
    memset(FB, 0, sizeof FB);
}

JNIEXPORT jboolean JNICALL
Java_com_bernd_tamaemu_EmuNative_isRunning(JNIEnv *env, jclass c)
{
    return RUNNING ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_bernd_tamaemu_EmuNative_isLoaded(JNIEnv *env, jclass c)
{
    return LOADED ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_bernd_tamaemu_EmuNative_frame(JNIEnv *env, jclass c, jintArray out)
{
    if (!LOADED) return 0;
    if ((*env)->GetArrayLength(env, out) < PANEL_W * PANEL_H) return 0;
    pthread_mutex_lock(&FBLOCK);
    (*env)->SetIntArrayRegion(env, out, 0, PANEL_W * PANEL_H, (const jint *)FB);
    jlong n = (jlong)FRAME_NO;
    pthread_mutex_unlock(&FBLOCK);
    return n;
}

JNIEXPORT void JNICALL
Java_com_bernd_tamaemu_EmuNative_buttonDown(JNIEnv *env, jclass c, jint bit)
{
    if (bit < 1 || bit > 4) return;
    HELD |= (uint8_t)bit;
    for (int i = 0; i < 3; i++) if (bit & (1 << i)) HOLD_UNTIL[i] = now_ms() + MIN_HOLD_MS;
}

JNIEXPORT void JNICALL
Java_com_bernd_tamaemu_EmuNative_buttonUp(JNIEnv *env, jclass c, jint bit)
{
    HELD &= (uint8_t)~bit;
}

JNIEXPORT void JNICALL
Java_com_bernd_tamaemu_EmuNative_tap(JNIEnv *env, jclass c, jint bit)
{
    for (int i = 0; i < 3; i++) if (bit & (1 << i)) HOLD_UNTIL[i] = now_ms() + TAP_MS;
}

JNIEXPORT void JNICALL
Java_com_bernd_tamaemu_EmuNative_setSpeed(JNIEnv *env, jclass c, jint mult)
{
    if (mult < 1) mult = 1;
    SPEED = mult;
    E.rtc_mult = mult;
}

JNIEXPORT jint JNICALL
Java_com_bernd_tamaemu_EmuNative_speedStep(JNIEnv *env, jclass c, jint dir)
{
    int m = periph_speed_step(E.rtc_mult > 0 ? E.rtc_mult : 1, dir);
    SPEED = m; E.rtc_mult = m;
    return m;
}

JNIEXPORT void JNICALL
Java_com_bernd_tamaemu_EmuNative_setStayAwake(JNIEnv *env, jclass c, jboolean on)
{
    STAYAWAKE = on ? 1 : 0;
    E.stay_awake = on ? true : false;
}

JNIEXPORT jboolean JNICALL
Java_com_bernd_tamaemu_EmuNative_isAsleep(JNIEnv *env, jclass c)
{
    return ASLEEP ? JNI_TRUE : JNI_FALSE;
}

/*
 * Ton: der Emulator-Faden ERZEUGT, der Tonfaden HOLT nur ab.
 *
 * Erste Fassung liess den Tonfaden alle 10 ms selbst rechnen. Der Emulator
 * arbeitet aber in Schueben von 1/60 s: mal lagen 16,7 ms Emulationszeit
 * bereit, mal null. Wer daraus 10-ms-Bloecke schneidet, bekommt bei jedem
 * Block einen anderen Rest - das hoert man als Rauheit, auch wenn die Noten
 * jetzt stimmen.
 *
 * Deshalb wird nun dort erzeugt, wo die Zeit bekannt ist: unmittelbar nach
 * jedem emulierten Bild, fuer genau die Zyklen dieses Bildes. Die Werte gehen
 * in einen Ring von rund einer halben Sekunde, aus dem der Tonfaden abzieht.
 * Ein Erzeuger, ein Verbraucher - deshalb genuegen die beiden Zaehler.
 */
#define AUD_RING 32768u
static int16_t          AUDR[AUD_RING];
static volatile unsigned AUD_W, AUD_R;      /* insgesamt geschrieben/gelesen */
static volatile int      AUD_RATE = 48000;
static volatile int      AUD_AMP = 4500;
static volatile unsigned AUD_UNDER;         /* Nachschub kam zu spaet */

/* Zustand des Erzeugers, nur im Emulator-Faden benutzt. */
static uint64_t aud_cyc;
static unsigned aud_ev_r;
static double   aud_phase, aud_frac;
static uint8_t  aud_on;
static double   aud_freq;

static void aud_put(int16_t v)
{
    if (AUD_W - AUD_R >= AUD_RING) AUD_R++;   /* niemand hoert zu: aeltestes weg */
    AUDR[AUD_W % AUD_RING] = v;
    AUD_W++;
}

static int16_t aud_sample(void)
{
    if (!aud_on || aud_freq < 20.0 || aud_freq > 20000.0) return 0;
    aud_phase += aud_freq / (double)AUD_RATE;
    if (aud_phase >= 1.0) aud_phase -= 1.0;
    return (int16_t)(aud_phase < 0.5 ? AUD_AMP : -AUD_AMP);
}

/** Alles bis E.cycles in den Ring rechnen. Laeuft im Emulator-Faden. */
static void aud_render(void)
{
    double mclk = E.cmu.mclk_hz > 0 ? E.cmu.mclk_hz : E.dev.osc3_hz;
    if (mclk <= 0) return;
    if (!aud_cyc) aud_cyc = E.cycles;

    /* Nach einem Haenger oder uebergelaufener Ereignisliste neu aufsetzen. */
    if ((double)(E.cycles - aud_cyc) > mclk / 2 || E.tone_ev_w - aud_ev_r > TONE_EV_N) {
        aud_cyc = E.cycles;
        while (aud_ev_r < E.tone_ev_w) {
            struct ToneEv *ev = &E.tone_ev[aud_ev_r % TONE_EV_N];
            aud_on = ev->on; aud_freq = ev->freq; aud_ev_r++;
        }
    }

    while (aud_cyc < E.cycles) {
        uint64_t next = E.cycles;
        if (aud_ev_r < E.tone_ev_w) {
            struct ToneEv *ev = &E.tone_ev[aud_ev_r % TONE_EV_N];
            if (ev->cyc <= aud_cyc) {
                aud_on = ev->on; aud_freq = ev->freq; aud_ev_r++;
                continue;
            }
            if (ev->cyc < next) next = ev->cyc;
        }
        double smp = (double)(next - aud_cyc) * (double)AUD_RATE / mclk + aud_frac;
        int k = (int)smp;
        aud_frac = smp - k;
        for (int i = 0; i < k; i++) aud_put(aud_sample());
        aud_cyc = next;
    }
}

JNIEXPORT void JNICALL
Java_com_bernd_tamaemu_EmuNative_audioVolume(JNIEnv *env, jclass c, jint percent)
{
    if (percent < 0) percent = 0;
    if (percent > 100) percent = 100;
    AUD_AMP = percent * 90;          /* 100 % entspricht der Desktop-Lautstaerke */
}

/** Abtastrate setzen und den Ring leeren (beim Start des Tonfadens). */
JNIEXPORT void JNICALL
Java_com_bernd_tamaemu_EmuNative_audioStart(JNIEnv *env, jclass c, jint rate)
{
    if (rate >= 8000 && rate <= 96000) AUD_RATE = rate;
    AUD_R = AUD_W;
    AUD_UNDER = 0;
}

/**
 * Holt `frames` Abtastwerte aus dem Ring. Rueckgabe: wie viele davon echt
 * waren. Fehlende werden still aufgefuellt - das passiert nur, wenn die
 * Emulation gerade haengt.
 */
JNIEXPORT jint JNICALL
Java_com_bernd_tamaemu_EmuNative_audioPull(JNIEnv *env, jclass c,
                                           jshortArray out, jint frames)
{
    static int16_t buf[4096];
    if (frames < 1) return 0;
    if (frames > (jint)(sizeof buf / sizeof buf[0])) frames = (jint)(sizeof buf / sizeof buf[0]);

    unsigned have = AUD_W - AUD_R;
    int n = 0;
    while (n < frames && (unsigned)n < have) {
        buf[n] = AUDR[(AUD_R + (unsigned)n) % AUD_RING];
        n++;
    }
    AUD_R += (unsigned)n;
    int real = n;
    if (n < frames) {
        AUD_UNDER++;
        while (n < frames) buf[n++] = 0;
    }
    (*env)->SetShortArrayRegion(env, out, 0, frames, buf);
    return real;
}

/** Wie viele Abtastwerte gerade bereitliegen. */
JNIEXPORT jint JNICALL
Java_com_bernd_tamaemu_EmuNative_audioAvail(JNIEnv *env, jclass c)
{
    return (jint)(AUD_W - AUD_R);
}


/* 0 = still, sonst Frequenz in Hz. */
JNIEXPORT jint JNICALL
Java_com_bernd_tamaemu_EmuNative_tone(JNIEnv *env, jclass c)
{
    if (!LOADED || !E.tone_on) return 0;
    float f = E.tone_freq;
    if (f < 20.0f || f > 20000.0f) return 0;
    return (jint)f;
}

JNIEXPORT void JNICALL
Java_com_bernd_tamaemu_EmuNative_persist(JNIEnv *env, jclass c, jboolean force)
{
    WANT_PERSIST = force ? 2 : 1;
    if (!RUNNING && LOADED) persist_now(force ? 1 : 0);
}

/**
 * ROM neu starten (wie Batterie raus und rein). Ist der Kern vorher an einer
 * ungueltigen Anweisung gestoppt, ist auch sein Thread beendet - dann wird er
 * hier wieder angeworfen, sonst passiert nichts sichtbares.
 */
JNIEXPORT void JNICALL
Java_com_bernd_tamaemu_EmuNative_resetRom(JNIEnv *env, jclass c)
{
    if (!LOADED) return;
    if (RUNNING) { WANT_RESET = 1; return; }
    cpu_reset(&E);
    RESET_VEC = E.pc;
    STOP_PC = 0;
    E.stopped = false;
    E.stop_reason[0] = 0;
    WANT_RESET = 0;
    WANT_STOP = 0;
    if (pthread_create(&TH, NULL, emu_thread, NULL) != 0)
        LOGE("[cpu] Thread laesst sich nicht starten");
}



/* ------------------------------------------------- Zeitausgleich (Nachlauf) */

/**
 * Offene Spielzeit nachholen, ohne die Uhr zu stellen: die Firmware bekommt
 * jede Sekunde zu sehen, nur schneller. Deshalb wird keine Pflegeaktion
 * uebersprungen - sie laufen nur zusammengerafft ab.
 */
JNIEXPORT void JNICALL
Java_com_bernd_tamaemu_EmuNative_catchUp(JNIEnv *env, jclass c, jdouble secs, jint mult)
{
    if (mult < 2) mult = 2;
    if (mult > 120) mult = 120;      /* darueber kann der 1/64-s-RTC-IRQ Takte verlieren */
    if (secs < 0) secs = 0;
    CATCH_MULT = mult;
    CATCH_DEF = secs;
    LOGI("[nachlauf] %.0f s offen, Faktor x%d", (double)secs, mult);
}

/** Noch offene Nachlaufzeit in Sekunden (0 = fertig). */
JNIEXPORT jdouble JNICALL
Java_com_bernd_tamaemu_EmuNative_catchLeft(JNIEnv *env, jclass c)
{
    return (jdouble)CATCH_DEF;
}


/* --------------------------------------------------------------- Besuch */

/**
 * Auto-Link: der Kern oeffnet die Verbindung selbst, sobald die Firmware in
 * den Verbindungsmodus geht, und verbindet sich dabei auf 127.0.0.1:PORT.
 * Den Weg ins WLAN legt die Bruecke auf der Kotlin-Seite (VisitBridge).
 */
JNIEXPORT void JNICALL
Java_com_bernd_tamaemu_EmuNative_linkAuto(JNIEnv *env, jclass c, jboolean on, jint port)
{
    LINK_ON = on ? 1 : 0;
    if (port > 0) LINK_PORT = port;
    E.auto_link = LINK_ON ? true : false;
    E.auto_link_port = LINK_PORT;
    if (!LINK_ON) LINK_WANT_CLOSE = 1;
}

/** 0 = aus, 1 = wartet, 2 = verbunden. */
JNIEXPORT jint JNICALL
Java_com_bernd_tamaemu_EmuNative_linkState(JNIEnv *env, jclass c)
{
    if (!LINK_ON) return 0;
    if (E.link) return 2;
    return 1;
}

/* ------------------------------------------- RAM-Schnappschuss (Rufsuche) */



/* ------------------------------------------------------------------- DLC */


/**
 * Nutzlasten in das Flash-Abbild schreiben. Der Kern MUSS vorher entladen
 * sein, sonst ueberschreibt der naechste Speicherlauf die Installation.
 * Vor dem Schreiben legt dlc_inject selbst eine .bak-Kopie an.
 */
JNIEXPORT jstring JNICALL
Java_com_bernd_tamaemu_EmuNative_dlcInstall(JNIEnv *env, jclass cls, jstring jdev,
                                            jstring jimg, jobjectArray jpaths,
                                            jboolean wipe)
{
    char b[4096];
    int n = 0;
    const char *dev = (*env)->GetStringUTFChars(env, jdev, 0);
    const char *img = (*env)->GetStringUTFChars(env, jimg, 0);
    const DlcDevice *d = dlc_device_find(dev);

    int np = (*env)->GetArrayLength(env, jpaths);
    if (np < 1 || np > 32) {
        (*env)->ReleaseStringUTFChars(env, jdev, dev);
        (*env)->ReleaseStringUTFChars(env, jimg, img);
        return (*env)->NewStringUTF(env, "Keine (oder zu viele) Dateien gewaehlt.");
    }

    const char *paths[32];
    jstring js[32];
    for (int i = 0; i < np; i++) {
        js[i] = (jstring)(*env)->GetObjectArrayElement(env, jpaths, i);
        paths[i] = (*env)->GetStringUTFChars(env, js[i], 0);
    }

    DlcResult res[32];
    char err[DLC_ERR_MAX] = "";
    int rc = dlc_inject(d, img, paths, np, wipe ? 1 : 0, 1, NULL, 0, res, err, sizeof err);

    if (rc != 0) {
        n += snprintf(b + n, sizeof b - n, "Nichts geschrieben: %s\n", err);
    } else {
        int ok = 0;
        for (int i = 0; i < np && n < (int)sizeof b - 200; i++) {
            DlcResult *r = &res[i];
            if (r->error[0]) {
                /* Die haeufigste Ursache in Klartext, der Rest im Original. */
                const char *hint = strstr(r->error, "store full")
                    ? "Kein freier Platz in dieser Kategorie. "
                      "Erst unter \"Belegte Plaetze\" einen freigeben."
                    : NULL;
                n += snprintf(b + n, sizeof b - n, "FEHLER  %s: %s\n", r->file, r->error);
                if (hint) n += snprintf(b + n, sizeof b - n, "        %s\n", hint);
            } else {
                ok++;
                const char *en = dlc_4u_name_en(r->id);
                n += snprintf(b + n, sizeof b - n, "OK      %s%s%s%s\n",
                              r->name[0] ? r->name : r->file,
                              r->label ? "  [" : "", r->label ? r->label : "",
                              r->label ? "]" : "");
                if (en) n += snprintf(b + n, sizeof b - n, "        (%s)\n", en);
            }
        }
        n += snprintf(b + n, sizeof b - n, "\n%d von %d installiert.\n", ok, np);
    }

    for (int i = 0; i < np; i++) {
        (*env)->ReleaseStringUTFChars(env, js[i], paths[i]);
        (*env)->DeleteLocalRef(env, js[i]);
    }
    (*env)->ReleaseStringUTFChars(env, jdev, dev);
    (*env)->ReleaseStringUTFChars(env, jimg, img);
    return (*env)->NewStringUTF(env, b);
}

/* ------------------------------------------------------- Firmware pruefen */


/** Geraetename, dessen Signatur zu dieser Datei passt ("" wenn unbekannt). */
JNIEXPORT jstring JNICALL
Java_com_bernd_tamaemu_EmuNative_romGuess(JNIEnv *env, jclass cls, jstring jpath)
{
    const char *path = (*env)->GetStringUTFChars(env, jpath, 0);
    const DlcDevice *d = dlc_device_of_image(path);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return (*env)->NewStringUTF(env, d ? d->name : "");
}





/* -------------------------------------------------------- Beobachtung */





/* ------------------------------------------------------------ Ruf-Erkennung */

/*
 * Im Labor an der echten Firmware gemessen (FINDINGS v11):
 * Der Ruf haengt an ZWEI unabhaengigen Beduerfniszaehlern im DSTRAM, die jede
 * Spielminute heruntergezaehlt werden. Laeuft einer auf 0, wird er neu gesetzt
 * UND das Tama ruft. Genau diese beiden Ruecksetzstellen sind der Haken:
 *     0x02406948  setzt 0x84228 wieder auf 3   (Zaehler mit 4 Minuten)
 *     0x024069ca  setzt 0x8422d wieder auf 5   (Zaehler mit 6 Minuten)
 * Ueber fuenf Rufe hinweg deckungsgleich, und an den Minuten ohne Ruf lief
 * keine der beiden Stellen. Die Adressen gelten fuer diesen Firmwarestand und
 * kommen deshalb von aussen.
 *
 * Zweiter, versionsunabhaengiger Weg: die Rufmelodie selbst beginnt mit
 * 1396 Hz gefolgt von 1174 Hz. Der Tastenton (659 Hz) und die
 * Bestaetigungsmelodie beim Fuettern (659/1046/1318) fangen anders an.
 */

/** Haken auf die Ruecksetzstellen legen ("2406948,24069ca"). */
JNIEXPORT jint JNICALL
Java_com_bernd_tamaemu_EmuNative_callHookSet(JNIEnv *env, jclass c, jstring jlist)
{
    const char *list = (*env)->GetStringUTFChars(env, jlist, 0);
    int n = 0;
    const char *p = list;
    while (*p && n < CALLHOOK_N) {
        while (*p == ' ' || *p == ',') p++;
        if (!*p) break;
        char *end;
        unsigned long a = strtoul(p, &end, 16);
        if (end == p) break;
        CALL_HOOK[n++] = (uint32_t)a;
        p = end;
    }
    CALL_HOOK_N = n;
    (*env)->ReleaseStringUTFChars(env, jlist, list);
    LOGI("[ruf] %d Haken gesetzt", n);
    return n;
}

/** Melodieerkennung an/aus (Rueckfall fuer Geraete ohne gemessene Haken). */
JNIEXPORT void JNICALL
Java_com_bernd_tamaemu_EmuNative_callMelody(JNIEnv *env, jclass c, jboolean on)
{
    CALL_MELODY = on ? 1 : 0;
}

/** 1 = es ruft, 0 = ruhig. */
JNIEXPORT jint JNICALL
Java_com_bernd_tamaemu_EmuNative_callState(JNIEnv *env, jclass c)
{
    return CALLING;
}


/** Ruf abhaken - die App ruft das, wenn gepflegt wurde. */
JNIEXPORT void JNICALL
Java_com_bernd_tamaemu_EmuNative_callClear(JNIEnv *env, jclass c)
{
    if (CALLING) wlog("%8.1f  RUF abgehakt\n", E.emu_secs);
    CALLING = 0;
}


/* -------------------------------------------------- Flaechensuche */



/** Nach wie vielen Sekunden ein unbeantworteter Ruf verfaellt (0 = nie). */
JNIEXPORT void JNICALL
Java_com_bernd_tamaemu_EmuNative_callExpiry(JNIEnv *env, jclass c, jint secs)
{
    CALL_EXPIRY = secs < 0 ? 0 : (double)secs;
}


/** Aktueller Rueckstand der Spieluhr in Sekunden. */
JNIEXPORT jdouble JNICALL
Java_com_bernd_tamaemu_EmuNative_driftNow(JNIEnv *env, jclass c)
{
    return (jdouble)DRIFT_NOW;
}


/* ------------------------------------------------- DLC: Plaetze verwalten */

/**
 * Alle Speicherplaetze auflisten. Die Faecher sind je Art fest und knapp -
 * beim iD L gibt es zum Beispiel nur ZWEI Plaetze fuer heruntergeladene
 * Spiele. Eingebaute Spiele liegen woanders; sie zu loeschen macht hier
 * keinen Platz frei.
 * Rueckgabe je Zeile: art|fach|belegt|kennung|name|beschriftung
 */
JNIEXPORT jstring JNICALL
Java_com_bernd_tamaemu_EmuNative_dlcSlots(JNIEnv *env, jclass cls, jstring jdev, jstring jimg)
{
    static char b[16000];
    int n = 0;
    const char *dev = (*env)->GetStringUTFChars(env, jdev, 0);
    const char *img = (*env)->GetStringUTFChars(env, jimg, 0);
    const DlcDevice *d = dlc_device_find(dev);

    if (!d || dlc_kind_count(d) == 0) {
        (*env)->ReleaseStringUTFChars(env, jdev, dev);
        (*env)->ReleaseStringUTFChars(env, jimg, img);
        return (*env)->NewStringUTF(env, "");
    }

    uint32_t sz = dlc_image_size(d);
    uint8_t *buf = malloc(sz);
    FILE *f = buf ? fopen(img, "rb") : NULL;
    if (f && fread(buf, 1, sz, f) == sz) {
        for (int i = 0; i < dlc_kind_count(d) && n < (int)sizeof b - 400; i++) {
            const DlcKind *k = dlc_kind_at(d, i);
            if (!k) continue;
            DlcSlot sl[32];
            int ns = dlc_store_slots(d, buf, k->kind, sl, 32);
            for (int s = 0; s < ns && n < (int)sizeof b - 400; s++)
                n += snprintf(b + n, sizeof b - n, "%d|%d|%d|%s|%s|%s\n",
                              k->kind, sl[s].slot, sl[s].occupied,
                              sl[s].id, sl[s].name, k->label);
        }
    }
    if (f) fclose(f);
    free(buf);

    (*env)->ReleaseStringUTFChars(env, jdev, dev);
    (*env)->ReleaseStringUTFChars(env, jimg, img);
    return (*env)->NewStringUTF(env, b);
}

/** Einen Platz leeren. Der Kern muss entladen sein. "" = geschafft. */
JNIEXPORT jstring JNICALL
Java_com_bernd_tamaemu_EmuNative_dlcFreeSlot(JNIEnv *env, jclass cls, jstring jdev,
                                             jstring jimg, jint kind, jint slot)
{
    char msg[200] = "";
    const char *dev = (*env)->GetStringUTFChars(env, jdev, 0);
    const char *img = (*env)->GetStringUTFChars(env, jimg, 0);
    const DlcDevice *d = dlc_device_find(dev);

    if (!d) snprintf(msg, sizeof msg, "Geraet unbekannt.");
    else {
        uint32_t sz = dlc_image_size(d);
        uint8_t *buf = malloc(sz);
        FILE *f = buf ? fopen(img, "rb") : NULL;
        if (!f || fread(buf, 1, sz, f) != sz) {
            snprintf(msg, sizeof msg, "Spielstand nicht lesbar.");
        } else {
            fclose(f); f = NULL;
            if (dlc_free_slot(d, buf, kind, slot) < 0) {
                snprintf(msg, sizeof msg, "Platz %d der Art %d gibt es nicht.", slot, kind);
            } else {
                FILE *o = fopen(img, "wb");
                if (!o || fwrite(buf, 1, sz, o) != sz)
                    snprintf(msg, sizeof msg, "Schreiben fehlgeschlagen.");
                if (o) fclose(o);
            }
        }
        if (f) fclose(f);
        free(buf);
    }
    (*env)->ReleaseStringUTFChars(env, jdev, dev);
    (*env)->ReleaseStringUTFChars(env, jimg, img);
    return (*env)->NewStringUTF(env, msg);
}

/* ------------------------------------------------- Vorlaufzeit des Besuchs */

/**
 * Die Vorlaufzeit ist ein Kompromiss: zu wenig, und Zittern auf der Strecke
 * verdirbt Bytes; zu viel, und die Zeitschranken der Firmware laufen ab, weil
 * jedes Byte entsprechend spaeter zugestellt wird. Am Geraet gemessen: bei
 * 20 ms fliessen die Bytes sauber (null Kollisionen), der Handschlag scheitert
 * trotzdem - das spricht fuer zu viel Vorlauf, nicht fuer zu wenig.
 */
JNIEXPORT void JNICALL
Java_com_bernd_tamaemu_EmuNative_linkLead(JNIEnv *env, jclass c, jint us)
{
    link_set_lead_us((unsigned)us);
    LOGI("[besuch] Vorlaufzeit %d us", us);
}


/* ------------------------------------------------------------------- Uhr */

/**
 * Uhr des Geraets stellen. Die Firmware liest Stunde, Minute und Sekunde aus
 * den RTC-Registern (BCD, 24-Stunden-Zaehlung); periph.c zaehlt sie von dort
 * aus weiter. Das ist ein SPRUNG - im Gegensatz zum Nachholen, das jede
 * Sekunde durchlaufen laesst. Deshalb nur auf ausdruecklichen Wunsch.
 */
JNIEXPORT jboolean JNICALL
Java_com_bernd_tamaemu_EmuNative_setClock(JNIEnv *env, jclass c,
                                          jint h, jint m, jint s)
{
    if (!LOADED) return JNI_FALSE;
    if (h < 0 || h > 23 || m < 0 || m > 59 || s < 0 || s > 59) return JNI_FALSE;

    uint32_t base = E.dev.io_base;
    uint32_t off_s = 0x301910u - base, off_m = 0x301914u - base, off_h = 0x301918u - base;
    if (off_s >= IORAM_MAX || off_m >= IORAM_MAX || off_h >= IORAM_MAX) return JNI_FALSE;

    E.ioram[off_s] = (uint8_t)(((s / 10) << 4) | (s % 10));
    E.ioram[off_m] = (uint8_t)(((m / 10) << 4) | (m % 10));
    E.ioram[off_h] = (uint8_t)(((h / 10) << 4) | (h % 10));
    LOGI("[uhr] gestellt auf %02d:%02d:%02d", h, m, s);
    return JNI_TRUE;
}

/**
 * Wie schnell die Emulation gegenueber der Echtzeit laeuft, in Prozent.
 * Auf einem ausreichend flotten Geraet steht das bei 100. Faellt es deutlich
 * darunter, laeuft das Tama langsamer als das echte Vorbild - fuer den
 * Benutzer die einzig interessante Aussage ueber die Leistung seines Geraets.
 */
JNIEXPORT jint JNICALL
Java_com_bernd_tamaemu_EmuNative_speedPercent(JNIEnv *env, jclass c)
{
    if (!LOADED || !RUNNING) return 0;
    double mclk = E.cmu.mclk_hz > 0 ? E.cmu.mclk_hz : E.dev.osc3_hz;
    if (mclk <= 0) return 0;
    double p = MIPS * 1e6 / mclk * 100.0;
    if (p < 0) p = 0;
    if (p > 999) p = 999;
    return (jint)(p + 0.5);
}
