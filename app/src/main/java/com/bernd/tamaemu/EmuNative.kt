package com.bernd.tamaemu

/**
 * Bruecke zum Emulatorkern (native-emu.c). Der Emulator ist ein Singleton im
 * Prozess: App, Dienst und Widget sprechen denselben Kern an.
 */
object EmuNative {
    const val W = 128
    const val H = 128

    const val BTN_A = 1
    const val BTN_B = 2
    const val BTN_C = 4

    init { System.loadLibrary("tamaemu") }

    /** Liste "name|Titel" aller Geraeteprofile aus device.c. */
    external fun devices(): Array<String>

    /** Leerer String = ok, sonst Fehlertext. */
    external fun init(
        romPath: String, device: String, savPath: String, ramPath: String,
        statePath: String, buildId: String
    ): String

    external fun start(): Boolean
    external fun stop()
    external fun unload()
    external fun isRunning(): Boolean
    external fun isLoaded(): Boolean

    /** Kopiert 128x128 ARGB ins Array, gibt die Bildnummer zurueck. */
    external fun frame(px: IntArray): Long

    external fun buttonDown(bit: Int)
    external fun buttonUp(bit: Int)
    /** Ein sauberer, kurzer Druck (fuer Widget-Taps). */
    external fun tap(bit: Int)

    external fun setSpeed(mult: Int)
    external fun speedStep(dir: Int): Int
    external fun setStayAwake(on: Boolean)
    external fun isAsleep(): Boolean
    /** Tempo der Emulation in Prozent der Echtzeit (100 = wie das Original). */
    external fun speedPercent(): Int

    /** 0 = still, sonst Tonhoehe in Hz. */
    external fun tone(): Int

    /** Uhr des Geraets stellen (Sprung, kein Nachlauf). */
    external fun setClock(hour: Int, minute: Int, second: Int): Boolean

    external fun persist(force: Boolean)
    external fun resetRom()

    // --- Ton ---
    external fun audioStart(rate: Int)
    external fun audioPull(out: ShortArray, frames: Int): Int
    external fun audioVolume(percent: Int)
    external fun audioAvail(): Int

    // --- Zeitausgleich ---
    external fun catchUp(secs: Double, mult: Int)
    external fun catchLeft(): Double
    external fun driftNow(): Double

    // --- Besuch ---
    external fun linkAuto(on: Boolean, port: Int)
    external fun linkState(): Int
    external fun linkLead(us: Int)

    // --- Ruf ---
    external fun callHookSet(list: String): Int
    external fun callMelody(on: Boolean)
    external fun callState(): Int
    external fun callClear()
    external fun callExpiry(secs: Int)

    // --- Zusatzinhalte ---
    /** Geraetename laut Signatur der Datei, "" wenn unbekannt. */
    external fun romGuess(path: String): String
    external fun dlcSlots(device: String, imgPath: String): String
    external fun dlcFreeSlot(device: String, imgPath: String, kind: Int, slot: Int): String
    external fun dlcInstall(
        device: String, imgPath: String, paths: Array<String>, wipe: Boolean
    ): String
}
