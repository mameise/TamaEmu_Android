package com.bernd.tamaemu

/**
 * Alle Tastendruecke laufen hier durch. Der Zeitstempel sagt der
 * Ruf-Erkennung, wann zuletzt jemand am Geraet war: wer eine Taste drueckt,
 * kuemmert sich, also ist der Ruf beantwortet.
 */
object Input {
    @Volatile var lastPressMs = 0L; private set

    fun down(bit: Int) { lastPressMs = System.currentTimeMillis(); EmuNative.buttonDown(bit) }
    fun up(bit: Int) { lastPressMs = System.currentTimeMillis(); EmuNative.buttonUp(bit) }
    fun tap(bit: Int) { lastPressMs = System.currentTimeMillis(); EmuNative.tap(bit) }
}
