package com.bernd.tamaemu

import android.content.Context
import java.io.File

/* Einstellungen als Top-Level-Context-Extensions (gleiches Package -> ctx.x). */

private fun Context.emuSp() = getSharedPreferences("tamaemucfg", Context.MODE_PRIVATE)
private fun Context.putI(k: String, v: Int) { emuSp().edit().putInt(k, v).apply() }
private fun Context.putB(k: String, v: Boolean) { emuSp().edit().putBoolean(k, v).apply() }
private fun Context.putS(k: String, v: String) { emuSp().edit().putString(k, v).apply() }

// --- Emulation ---
var Context.device: String              // Geraeteprofil aus device.c (ps, idl, id, 4u, ...)
    get() = emuSp().getString("dev", "ps") ?: "ps"; set(v) = putS("dev", v)
var Context.speed: Int                  // Spieluhr x1..x600 (Leiter aus periph.c)
    get() = emuSp().getInt("speed", 1); set(v) = putI("speed", v)
var Context.stayAwake: Boolean          // Firmware nie einschlafen lassen (am Handy Vorgabe)
    get() = emuSp().getBoolean("awake", true); set(v) = putB("awake", v)
var Context.romName: String             // Anzeigename des importierten Dumps
    get() = emuSp().getString("romname", "") ?: ""; set(v) = putS("romname", v)

/** Sprache der Oberflaeche: "" = Systemsprache, sonst "en" oder "de". */
var Context.uiLanguage: String
    get() = emuSp().getString("lang", "") ?: ""; set(v) = putS("lang", v)

// --- Anzeige ---
var Context.eggColor: Int               // Ei-Gehaeusefarbe (Index in EggRenderer.EGG_SHELL)
    get() = emuSp().getInt("egg", 0); set(v) = putI("egg", v)
var Context.pixelGrid: Boolean          // sichtbares LCD-Raster im Vollbild
    get() = emuSp().getBoolean("grid", false); set(v) = putB("grid", v)

/**
 * Farbe der Knoepfe im Hauptbildschirm.
 *
 * Ohne eigene Angabe uebernimmt Android das Aussehen des Systemthemas - und
 * bei manchen Herstelleroberflaechen kommt dabei schwarz auf schwarz heraus.
 * Deshalb zeichnen wir die Knoepfe selbst, mit fest gewaehlter Farbe.
 */
var Context.btnColor: Int
    get() = emuSp().getInt("btncolor", 0); set(v) = putI("btncolor", v)

// --- Bedienung ---
/** Tempo-Knoepfe zusaetzlich auf dem Hauptbildschirm zeigen. */
var Context.speedOnUi: Boolean
    get() = emuSp().getBoolean("speedui", false); set(v) = putB("speedui", v)
/** A/B/C-Knoepfe auf dem Hauptbildschirm zeigen (aus bei reiner Gamepad-Bedienung). */
var Context.showButtons: Boolean
    get() = emuSp().getBoolean("showbtns", true); set(v) = putB("showbtns", v)
/** Vollbild: ohne Titel- und Systemleisten, Bild so gross wie moeglich. */
var Context.fullscreen: Boolean
    get() = emuSp().getBoolean("fullscreen", false); set(v) = putB("fullscreen", v)

/**
 * Im Vollbild: Pixel gleich gross halten (ganzzahlig skalieren) statt die
 * Flaeche auszunutzen. Scharf, aber kleiner - beides hat seine Berechtigung.
 */
var Context.sharpPixels: Boolean
    get() = emuSp().getBoolean("sharppx", true); set(v) = putB("sharppx", v)

/** Bedienung ueber ein angeschlossenes Gamepad. */
var Context.gamepad: Boolean
    get() = emuSp().getBoolean("gamepad", false); set(v) = putB("gamepad", v)
/** Tastencodes des Gamepads fuer A, B und C. */
var Context.padA: Int
    get() = emuSp().getInt("pada", android.view.KeyEvent.KEYCODE_BUTTON_A); set(v) = putI("pada", v)
var Context.padB: Int
    get() = emuSp().getInt("padb", android.view.KeyEvent.KEYCODE_BUTTON_B); set(v) = putI("padb", v)
var Context.padC: Int
    get() = emuSp().getInt("padc", android.view.KeyEvent.KEYCODE_BUTTON_X); set(v) = putI("padc", v)

// --- Verhalten ---
var Context.bgRun: Boolean              // im Hintergrund weiterlaufen (Foreground-Service)
    get() = emuSp().getBoolean("bgrun", true); set(v) = putB("bgrun", v)
var Context.widgetMs: Int               // Widget-Takt in ms (500 = 2 Bilder/s)
    get() = emuSp().getInt("wms", 500); set(v) = putI("wms", v)
var Context.sound: Boolean              // Piezo-Ton nachbilden (AudioTrack)
    get() = emuSp().getBoolean("snd", true); set(v) = putB("snd", v)
var Context.soundVol: Int               // Lautstaerke in % (0..100)
    get() = emuSp().getInt("sndvol", 18); set(v) = putI("sndvol", v)
var Context.vibrate: Boolean            // bei Ton vibrieren
    get() = emuSp().getBoolean("vib", false); set(v) = putB("vib", v)
var Context.vibeMs: Int                 // Vibrationsdauer in ms
    get() = emuSp().getInt("vibems", 25); set(v) = putI("vibems", v)

/** Dateien: die Firmware liegt privat im App-Ordner und verlaesst das Geraet nie. */
object EmuFiles {
    /*
     * Mehrere Firmwares nebeneinander: jede liegt als roms/<kennung>.bin, die
     * aktive steht in den Einstellungen. Frueher gab es nur eine rom.bin -
     * die wird beim ersten Zugriff in die Sammlung uebernommen, damit niemand
     * neu importieren muss.
     */
    fun romsDir(c: Context): File = File(c.filesDir, "roms").apply { mkdirs() }

    fun romFile(c: Context, id: String): File = File(romsDir(c), "$id.bin")

    fun rom(c: Context): File {
        uebernimmAlteRom(c)
        val id = c.emuSp().getString("romid", "") ?: ""
        return if (id.isEmpty()) File(c.filesDir, "rom.bin") else romFile(c, id)
    }

    private fun uebernimmAlteRom(c: Context) {
        val alt = File(c.filesDir, "rom.bin")
        if (!alt.exists() || alt.length() < 0x1000) return
        val id = crc32(alt)
        val ziel = romFile(c, id)
        if (!ziel.exists()) alt.copyTo(ziel, overwrite = true)
        alt.delete()
        if ((c.emuSp().getString("romid", "") ?: "").isEmpty()) setRomId(c, id)
        if (romName(c, id).isEmpty()) setRomName(c, id, c.romName.ifEmpty { "rom.bin" })
        if (romDevice(c, id).isEmpty()) setRomDevice(c, id, c.device)
    }

    /** Alle vorhandenen Firmwares, neueste zuerst. */
    fun romList(c: Context): List<String> =
        romsDir(c).listFiles()?.filter { it.name.endsWith(".bin") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name.removeSuffix(".bin") } ?: emptyList()

    fun romName(c: Context, id: String): String = c.emuSp().getString("romname_$id", "") ?: ""
    fun setRomName(c: Context, id: String, n: String) =
        c.emuSp().edit().putString("romname_$id", n).apply()
    fun romDevice(c: Context, id: String): String = c.emuSp().getString("romdev_$id", "") ?: ""
    fun setRomDevice(c: Context, id: String, d: String) =
        c.emuSp().edit().putString("romdev_$id", d).apply()

    fun crc32(f: File): String = runCatching {
        val crc = java.util.zip.CRC32()
        f.inputStream().use { ein ->
            val puffer = ByteArray(1 shl 16)
            while (true) {
                val n = ein.read(puffer)
                if (n <= 0) break
                crc.update(puffer, 0, n)
            }
        }
        java.lang.Long.toHexString(crc.value)
    }.getOrDefault("none")

    /** Firmware samt Spielstaenden entfernen. */
    fun removeRom(c: Context, id: String) {
        romFile(c, id).delete()
        c.filesDir.listFiles()?.filter { it.name.contains("_$id.") }?.forEach { it.delete() }
        c.emuSp().edit().remove("romname_$id").remove("romdev_$id").apply()
    }
    fun sav(c: Context, dev: String): File = mitKennung(c, dev, ".sav")
    fun ram(c: Context, dev: String): File = mitKennung(c, dev, ".sav.ram")
    fun hasRom(c: Context) = rom(c).length() > 0x1000
    fun ramSnap(c: Context, slot: Int): File = File(c.filesDir, "ramsnap${slot + 1}.bin")
    /**
     * Kennung der geladenen Firmware (CRC32 der Datei, hexadezimal).
     *
     * Spielstaende haengen daran statt nur am Geraeteprofil: wer zwischen
     * mehreren Firmwares wechselt, findet seinen Stand beim Zurueckwechseln
     * wieder, statt ihn beim Import zu verlieren.
     */
    fun romId(c: Context): String = c.emuSp().getString("romid", "") ?: "none"

    fun berechneRomId(c: Context): String = crc32(rom(c))

    fun setRomId(c: Context, id: String) {
        c.emuSp().edit().putString("romid", id).apply()
    }

    /**
     * Namen der Spielstandsdateien. Alte Staende ohne Kennung werden beim
     * ersten Zugriff umbenannt, damit niemand seinen Stand verliert.
     */
    private fun mitKennung(c: Context, device: String, endung: String): File {
        val neu = File(c.filesDir, "${device}_${romId(c)}$endung")
        if (!neu.exists()) {
            val alt = File(c.filesDir, "$device$endung")
            if (alt.exists()) alt.renameTo(neu)
        }
        return neu
    }

    /** Eigenes Widget-Bild des Benutzers, je Ausfuehrung. */
    fun skin(c: Context, art: EggRenderer.Art): File = File(
        c.filesDir, when (art) {
            EggRenderer.Art.GROSS -> "skin_big.png"
            EggRenderer.Art.HOCH -> "skin_tall.png"
            else -> "skin_small.png"      // Name beibehalten, damit vorhandene Bilder bleiben
        }
    )

    /** Vollstaendiger Zustand; wird nach einem App-Update verworfen. */
    fun state(c: Context, device: String): File = mitKennung(c, device, ".state")
}

// --- Zeitausgleich ---
var Context.catchupMult: Int            // 0 = aus, sonst Faktor der Spieluhr im Nachlauf
    get() = emuSp().getInt("catch", 60); set(v) = putI("catch", v)
var Context.lastWallMs: Long            // Wanduhr beim letzten Speichern
    get() = emuSp().getLong("wall", 0L); set(v) { emuSp().edit().putLong("wall", v).apply() }

// --- Vibration ---
var Context.vibMode: Int                // 0 = aus, 1 = bei jedem Ton, 2 = nur bei Ruf
    get() = emuSp().getInt("vibmode", 2); set(v) = putI("vibmode", v)
var Context.vibRepeatMin: Int           // Wiederholung in Minuten (0 = nicht wiederholen)
    get() = emuSp().getInt("vibrep", 0); set(v) = putI("vibrep", v)

// --- Ruf-Erkennung ---
/*
 * Ruf-Haken: die Stellen, an denen die Firmware einen abgelaufenen
 * Beduerfniszaehler neu setzt - genau dann ruft das Tama (FINDINGS v11).
 * Im Labor an fw_tg14_en_v53 gemessen; andere Firmwarestaende haben andere
 * Adressen, deshalb einstellbar. Ohne Haken traegt die Melodieerkennung.
 */
private val CALL_HOOKS = mapOf("idl" to "2406948,24069ca")

fun Context.callHooksFor(dev: String): String =
    emuSp().getString("callhooks_$dev", null) ?: (CALL_HOOKS[dev] ?: "")

fun Context.setCallHooks(dev: String, list: String) {
    emuSp().edit().putString("callhooks_$dev", list).apply()
}

/** Benachrichtigung, wenn das Tama Aufmerksamkeit braucht. */
var Context.notifyCall: Boolean
    get() = emuSp().getBoolean("notifycall", true); set(v) = putB("notifycall", v)

/** Nach wie vielen Minuten ein unbeantworteter Ruf von selbst verfaellt. */
var Context.callExpiryMin: Int
    get() = emuSp().getInt("callexp", 5); set(v) = putI("callexp", v)

/**
 * Der Benutzer hat die App bewusst geschlossen. Solange das gesetzt ist, faehrt
 * der Dienst nicht von selbst wieder hoch - weder ueber das Widget noch nach
 * einem Neustart des Handys. Geloescht wird es, wenn die App geoeffnet oder ein
 * Widget-Knopf gedrueckt wird.
 */
var Context.userQuit: Boolean
    get() = emuSp().getBoolean("userquit", false); set(v) = putB("userquit", v)

// --- Besuch ---
/**
 * Vorlaufzeit des Verbindungsspiels in Mikrosekunden. Kompromiss: zu wenig,
 * und Zittern verdirbt Bytes; zu viel, und die Zeitschranken der Firmware
 * laufen ab (FINDINGS v28).
 */
var Context.linkLeadUs: Int
    get() = emuSp().getInt("leadus", 1500); set(v) = putI("leadus", v)

var Context.visitPort: Int
    get() = emuSp().getInt("vport", 7878); set(v) = putI("vport", v)
var Context.visitHost: String
    get() = emuSp().getString("vhost", "") ?: ""; set(v) = putS("vhost", v)
