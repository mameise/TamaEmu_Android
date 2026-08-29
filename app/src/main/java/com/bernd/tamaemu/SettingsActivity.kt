package com.bernd.tamaemu

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.*

/** Einstellungen. Aufgebaut wie beim Tamago: Abschnitt, Knopf, kurzer Hinweis. */
class SettingsActivity : Activity() {

    private val REQ_ROM = 101
    private val REQ_SAVE_EXPORT = 102
    private val REQ_SAVE_IMPORT = 103
    private val REQ_DLC = 104
    private val REQ_SKIN = 105
    private val REQ_TEMPLATE = 106
    private var skinBig = false

    private lateinit var box: LinearLayout
    private lateinit var romStatus: TextView
    private lateinit var visitStatus: TextView
    private var speedButton: Button? = null

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        title = getString(R.string.settings)
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val scroll = ScrollView(this)
        scroll.addView(box)
        setContentView(scroll)
        build()
    }

    private fun build() {
        box.removeAllViews()

        header(R.string.sec_general)
        stateButton({ getString(R.string.language, LocaleHelper.label(this)) }) {
            LocaleHelper.next(this)
            recreate()          // Texte sofort neu aufbauen
        }
        hint(R.string.language_hint)

        header(R.string.sec_rom)
        romStatus = TextView(this)
        box.addView(romStatus)
        button(R.string.import_rom) { pickFile(REQ_ROM) }
        stateButton({ getString(R.string.device_lbl, devTitle()) }) { chooseDevice() }
        hint(R.string.device_hint)

        header(R.string.sec_clock)
        /*
         * Frueher aktualisierte sich nur der Knopf, der gedrueckt wurde - die
         * Beschriftung "Spieluhr: x5" blieb also stehen, wenn man ueber
         * "langsamer" auf x2 ging. Jetzt merken wir uns den Knopf und frischen
         * ihn aus setSpeed() auf, egal welcher der drei bedient wurde.
         */
        speedButton = stateButton({ getString(R.string.speed_lbl, speed) }) {
            setSpeed(EmuNative.speedStep(+1))
        }
        button(R.string.speed_down) { setSpeed(EmuNative.speedStep(-1)) }
        button(R.string.speed_reset) { EmuNative.setSpeed(1); setSpeed(1) }
        hint(R.string.speed_hint)
        stateButton({ getString(R.string.stay_awake, onOff(stayAwake)) }) {
            stayAwake = !stayAwake
            EmuNative.setStayAwake(stayAwake)
        }
        hint(R.string.stay_awake_hint)

        header(R.string.sec_display)
        stateButton({ getString(R.string.egg_color, eggName()) }) {
            eggColor = (eggColor + 1) % EggRenderer.EGG_COUNT
            EmuWidgetProvider.renderWidgets(this, force = true)
        }
        button(R.string.skin_pick_small) { pickSkin(false) }
        button(R.string.skin_pick_big) { pickSkin(true) }
        button(R.string.skin_template_small) { saveTemplate(false) }
        button(R.string.skin_template_big) { saveTemplate(true) }
        button(R.string.skin_reset) { resetSkins() }
        hint(R.string.skin_hint)

        stateButton({ getString(R.string.widget_rate, rateLabel()) }) {
            widgetMs = nextIn(widgetMs, intArrayOf(500, 1000, 2000))
        }

        header(R.string.sec_controls)
        stateButton({ getString(R.string.speed_on_ui, onOff(speedOnUi)) }) {
            speedOnUi = !speedOnUi
        }
        stateButton({ getString(R.string.show_buttons, onOff(showButtons)) }) {
            showButtons = !showButtons
        }
        stateButton({ getString(R.string.gamepad, onOff(gamepad)) }) { gamepad = !gamepad }
        button(R.string.pad_config) { padDialog() }
        hint(R.string.controls_hint)

        header(R.string.sec_sound)
        stateButton({ getString(R.string.sound_lbl, onOff(sound)) }) { sound = !sound }
        stateButton({ getString(R.string.sound_vol, volLabel()) }) {
            soundVol = when (soundVol) { in 0..12 -> 18; in 13..28 -> 40; else -> 8 }
        }
        stateButton({ getString(R.string.vib_mode, vibModeLabel()) }) {
            vibMode = (vibMode + 1) % 3
        }
        stateButton({ getString(R.string.vib_dur, vibeMs) }) {
            vibeMs = nextIn(vibeMs, intArrayOf(25, 60, 120, 300))
        }
        stateButton({ getString(R.string.vib_rep, vibRepLabel()) }) {
            vibRepeatMin = nextIn(vibRepeatMin, intArrayOf(0, 1, 2, 5))
        }
        stateButton({ getString(R.string.call_notify, onOff(notifyCall)) }) {
            notifyCall = !notifyCall
        }
        stateButton({ getString(R.string.call_expiry, expiryLabel()) }) {
            callExpiryMin = nextIn(callExpiryMin, intArrayOf(2, 5, 10, 0))
            EmuNative.callExpiry(callExpiryMin * 60)
        }
        hint(R.string.call_hint)

        header(R.string.sec_background)
        stateButton({ getString(R.string.bg_run, onOff(bgRun)) }) {
            bgRun = !bgRun
            if (bgRun) EmuService.start(this) else EmuService.stop(this)
        }
        stateButton({ getString(R.string.catchup, catchLabel()) }) {
            catchupMult = nextIn(catchupMult, intArrayOf(0, 30, 60, 120))
        }
        hint(R.string.catchup_hint)

        header(R.string.sec_save)
        button(R.string.save_export) { exportSave() }
        button(R.string.save_import) { pickFile(REQ_SAVE_IMPORT) }
        button(R.string.set_clock) { confirmSetClock() }
        hint(R.string.set_clock_hint)
        button(R.string.restart) { confirmRestart() }
        hint(R.string.restart_hint)
        button(R.string.wipe) { confirmWipe() }

        header(R.string.sec_dlc)
        button(R.string.dlc_install) { pickDlc() }
        button(R.string.dlc_slots) { slotsDialog() }
        hint(R.string.dlc_hint)

        header(R.string.sec_visit)
        visitStatus = TextView(this).apply { text = visitStatusText() }
        box.addView(visitStatus)
        hint2(getString(R.string.visit_hint, VisitBridge.localIps(), visitPort))
        button(R.string.visit_host) { VisitBridge.startHost(this, visitPort) }
        button(R.string.visit_join) { connectDialog() }
        button(R.string.visit_stop) { VisitBridge.stop() }
        stateButton({ getString(R.string.visit_lead, linkLeadUs / 1000.0) }) {
            linkLeadUs = nextIn(linkLeadUs, intArrayOf(1500, 3000, 6000, 20000))
            EmuNative.linkLead(linkLeadUs)
        }
        hint(R.string.visit_lead_hint)

        header(R.string.sec_app)
        button(R.string.app_quit) { confirmAppQuit() }
        hint(R.string.app_quit_hint)

        header(R.string.sec_about)
        button(R.string.about) { showAbout() }

        refresh()
    }

    // ------------------------------------------------------------- Aktionen

    private fun refresh() {
        romStatus.text = if (EmuFiles.hasRom(this)) {
            if (romName.isEmpty()) getString(R.string.rom_present)
            else getString(R.string.rom_present_name, romName)
        } else getString(R.string.rom_missing)
    }

    override fun onResume() {
        super.onResume()
        VisitBridge.onStatus = {
            runOnUiThread {
                if (::visitStatus.isInitialized) visitStatus.text = visitStatusText()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        VisitBridge.onStatus = null
    }

    /** Zustand der Bruecke in einen uebersetzten Satz uebersetzen. */
    private fun visitStatusText(): String {
        val d = VisitBridge.detail
        val txt = when (VisitBridge.state) {
            VisitBridge.Zustand.AUS -> getString(R.string.vs_off)
            VisitBridge.Zustand.WARTET -> getString(R.string.vs_waiting, d)
            VisitBridge.Zustand.KERN_DA -> getString(R.string.vs_core)
            VisitBridge.Zustand.GEGENSTELLE_DA -> getString(R.string.vs_peer, d)
            VisitBridge.Zustand.VERBINDE -> getString(R.string.vs_connecting, d)
            VisitBridge.Zustand.LAEUFT -> getString(R.string.vs_running)
            VisitBridge.Zustand.GETRENNT -> getString(R.string.vs_lost)
            VisitBridge.Zustand.FEHLER -> getString(R.string.vs_error, d)
        }
        return getString(R.string.visit_status, txt)
    }

    private fun setSpeed(m: Int) {
        speed = m
        speedButton?.text = getString(R.string.speed_lbl, m)
    }

    private fun devTitle(): String {
        val d = device
        return EmuNative.devices().firstOrNull { it.startsWith("$d|") }?.substringAfter('|') ?: d
    }

    private fun chooseDevice() {
        val devs = EmuNative.devices()
        val labels = devs.map { it.substringAfter('|') }.toTypedArray()
        val names = devs.map { it.substringBefore('|') }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sec_device))
            .setItems(labels) { _, which ->
                if (names[which] != device) {
                    EmuNative.persist(true)
                    device = names[which]
                    EmuService.reload(this)
                    build()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Nach dem Import: der Dump traegt keinen Namen, aber der Reset-Vektor ist
     * je Modell eindeutig - daraus kommt der Vorschlag.
     */
    private fun askDevice() {
        val devs = EmuNative.devices()
        val names = devs.map { it.substringBefore('|') }
        val guess = EmuNative.romGuess(EmuFiles.rom(this).absolutePath)
        val labels = devs.map {
            val n = it.substringBefore('|')
            if (n == guess) "\u25B6 " + it.substringAfter('|') else it.substringAfter('|')
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(
                if (guess.isEmpty()) getString(R.string.sec_device)
                else getString(R.string.device_guess)
            )
            .setItems(labels) { _, which ->
                device = names[which]
                EmuService.reload(this)
                build()
            }
            .setCancelable(false)
            .show()
    }

    private fun pickFile(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(i, req)
    }

    private fun pickDlc() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(i, REQ_DLC)
    }

    private fun exportSave() {
        if (!EmuFiles.sav(this, device).exists()) { toast(getString(R.string.no_save)); return }
        EmuNative.persist(true)
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
            .putExtra(Intent.EXTRA_TITLE, "$device.sav")
        startActivityForResult(i, REQ_SAVE_EXPORT)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != Activity.RESULT_OK) return
        val uri = data?.data ?: data?.clipData?.getItemAt(0)?.uri ?: return
        when (req) {
            REQ_ROM -> runCatching {
                EmuNative.persist(true)
                EmuNative.stop(); EmuNative.unload()
                contentResolver.openInputStream(uri)!!.use { input ->
                    EmuFiles.rom(this).outputStream().use { input.copyTo(it) }
                }
                romName = queryName(uri) ?: "rom.bin"
                EmuFiles.state(this, device).delete()      // andere Firmware
            }.onSuccess { refresh(); askDevice() }
                .onFailure { toast(getString(R.string.import_failed)) }

            REQ_SAVE_EXPORT -> runCatching {
                contentResolver.openOutputStream(uri)!!.use { out ->
                    EmuFiles.sav(this, device).inputStream().use { it.copyTo(out) }
                }
            }.onSuccess { toast(getString(R.string.done)) }
                .onFailure { toast(getString(R.string.import_failed)) }

            REQ_SAVE_IMPORT -> runCatching {
                EmuNative.stop(); EmuNative.unload()
                contentResolver.openInputStream(uri)!!.use { input ->
                    EmuFiles.sav(this, device).outputStream().use { input.copyTo(it) }
                }
                EmuFiles.ram(this, device).delete()
                EmuFiles.state(this, device).delete()
                EmuService.reload(this)
            }.onSuccess { toast(getString(R.string.done)) }
                .onFailure { toast(getString(R.string.import_failed)) }

            REQ_SKIN -> runCatching {
                contentResolver.openInputStream(uri)!!.use { input ->
                    EmuFiles.skin(this, skinBig).outputStream().use { input.copyTo(it) }
                }
                // Sofort einmal einlesen: was hier nicht als Bild durchgeht,
                // wuerde spaeter still zum Ei zurueckfallen.
                val ok = android.graphics.BitmapFactory
                    .decodeFile(EmuFiles.skin(this, skinBig).absolutePath) != null
                if (!ok) { EmuFiles.skin(this, skinBig).delete(); error("kein Bild") }
                EmuWidgetProvider.renderWidgets(this, force = true)
            }.onSuccess { toast(getString(R.string.done)) }
                .onFailure { toast(getString(R.string.skin_bad)) }

            REQ_TEMPLATE -> runCatching {
                val bmp = SkinTemplate.build(this, skinBig)
                contentResolver.openOutputStream(uri)!!.use { out ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
            }.onSuccess { toast(getString(R.string.done)) }
                .onFailure { toast(getString(R.string.import_failed)) }

            REQ_DLC -> {
                val list = ArrayList<Uri>()
                val clip = data?.clipData
                if (clip != null) for (i in 0 until clip.itemCount) list.add(clip.getItemAt(i).uri)
                else list.add(uri)
                installDlc(list)
            }
        }
    }

    /** Kern entladen, Dateien einspielen, Kern neu laden. */
    private fun installDlc(uris: List<Uri>) {
        if (!EmuFiles.hasRom(this)) { toast(getString(R.string.rom_missing)); return }
        runCatching {
            EmuNative.persist(true)
            Thread.sleep(400)
            EmuNative.stop(); EmuNative.unload()
            EmuFiles.state(this, device).delete()          // Flash geaendert

            val dir = java.io.File(cacheDir, "dlc").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val paths = ArrayList<String>()
            for ((k, u) in uris.withIndex()) {
                val nm = queryName(u) ?: "payload$k.bin"
                val f = java.io.File(dir, "${k}_$nm")
                contentResolver.openInputStream(u)!!.use { input ->
                    f.outputStream().use { input.copyTo(it) }
                }
                paths.add(f.absolutePath)
            }
            val savPath = EmuFiles.sav(this, device).absolutePath
            val report = EmuNative.dlcInstall(device, savPath, paths.toTypedArray(), false)
            val after = slotLines(savPath).joinToString("\n")
            EmuService.reload(this)
            report + "\n" + getString(R.string.dlc_after) + "\n" + after
        }.onSuccess { showText(getString(R.string.sec_dlc), it) }
            .onFailure {
                EmuService.reload(this)
                toast(getString(R.string.import_failed))
            }
    }

    private fun slotLines(savPath: String): List<String> =
        EmuNative.dlcSlots(device, savPath).lines().filter { it.isNotBlank() }.map { ln ->
            val f = ln.split("|")
            val busy = f.getOrElse(2) { "0" } == "1"
            val what = if (busy) f.getOrElse(4) { "" }.ifBlank { f.getOrElse(3) { "" } }
            else getString(R.string.dlc_slot_empty)
            "${f.getOrElse(5) { "?" }} ${f.getOrElse(1) { "?" }.toInt() + 1}: $what"
        }

    /** Die Plaetze sind fest und knapp - hier laesst sich einer leeren. */
    private fun slotsDialog() {
        val sav = EmuFiles.sav(this, device)
        if (!sav.exists()) { toast(getString(R.string.no_save)); return }
        val lines = EmuNative.dlcSlots(device, sav.absolutePath)
            .lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) { toast(getString(R.string.dlc_no_stores)); return }
        val labels = slotLines(sav.absolutePath).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlc_slots))
            .setItems(labels) { _, which ->
                val f = lines[which].split("|")
                if (f.getOrElse(2) { "0" } != "1") {
                    toast(getString(R.string.dlc_already_empty)); return@setItems
                }
                confirmFree(f[0].toInt(), f[1].toInt(), labels[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmFree(kind: Int, slot: Int, label: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlc_free_title))
            .setMessage(getString(R.string.dlc_free_msg, label))
            .setPositiveButton(getString(R.string.wipe)) { _, _ ->
                runCatching {
                    EmuNative.persist(true)
                    Thread.sleep(400)
                    EmuNative.stop(); EmuNative.unload()
                    EmuFiles.state(this, device).delete()
                    val err = EmuNative.dlcFreeSlot(
                        device, EmuFiles.sav(this, device).absolutePath, kind, slot
                    )
                    EmuService.reload(this)
                    err
                }.onSuccess { err ->
                    if (err.isEmpty()) toast(getString(R.string.done)) else toast(err)
                }.onFailure {
                    EmuService.reload(this)
                    toast(getString(R.string.import_failed))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Gamepad-Tasten festlegen: der Reihe nach A, B und C. Das Fenster nimmt
     * den naechsten Tastendruck als Belegung - so muss niemand Tastencodes
     * nachschlagen. Zurueck-Taste bricht ab, sonst kaeme man nicht mehr raus.
     */
    private fun padDialog() {
        askKey(R.string.pad_press_a) { a ->
            padA = a
            askKey(R.string.pad_press_b) { b ->
                padB = b
                askKey(R.string.pad_press_c) { c ->
                    padC = c
                    toast(getString(R.string.pad_saved, keyName(padA), keyName(padB), keyName(padC)))
                }
            }
        }
    }

    private fun askKey(promptRes: Int, done: (Int) -> Unit) {
        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.pad_config))
            .setMessage(getString(promptRes))
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dlg.setOnKeyListener { d, code, ev ->
            if (ev.action != android.view.KeyEvent.ACTION_UP) return@setOnKeyListener true
            if (code == android.view.KeyEvent.KEYCODE_BACK) { d.dismiss(); return@setOnKeyListener true }
            d.dismiss()
            done(code)
            true
        }
        dlg.show()
    }

    private fun keyName(code: Int): String =
        android.view.KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

    /*
     * Eigenes Widget-Bild. Der Ablauf ist bewusst zweistufig: erst die Vorlage
     * sichern und bemalen, dann das fertige Bild waehlen. Nur so weiss der
     * Benutzer, wo Bildschirm und Knoepfe liegen.
     */
    private fun pickSkin(big: Boolean) {
        skinBig = big
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        startActivityForResult(i, REQ_SKIN)
    }

    private fun saveTemplate(big: Boolean) {
        skinBig = big
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("image/png")
            .putExtra(
                Intent.EXTRA_TITLE,
                if (big) "tamaemu-skin-large.png" else "tamaemu-skin-compact.png"
            )
        startActivityForResult(i, REQ_TEMPLATE)
    }

    private fun resetSkins() {
        EmuFiles.skin(this, false).delete()
        EmuFiles.skin(this, true).delete()
        EmuWidgetProvider.renderWidgets(this, force = true)
        toast(getString(R.string.done))
    }

    private fun connectDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.ip_hint)
            setText(this@SettingsActivity.visitHost)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.visit_join))
            .setView(input)
            .setPositiveButton(getString(R.string.connect_btn)) { _, _ ->
                val t = input.text.toString().trim()
                val host = t.substringBefore(':').trim()
                val port = t.substringAfter(':', "").trim().toIntOrNull() ?: visitPort
                if (host.isNotEmpty()) {
                    visitHost = t
                    visitPort = port
                    VisitBridge.startClient(this, host, port)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Ein Knopf statt zwei. Laeuft der Kern, genuegt der Reset des Rechenwerks -
     * das ist Batterie raus und rein. Laeuft er nicht (oder ist er an einer
     * ungueltigen Anweisung stehengeblieben), hilft nur vollstaendiges
     * Neuladen von Firmware und Spielstand. Fuer den Benutzer ist beides
     * dasselbe: von vorn.
     */
    /**
     * Die Uhr des Tamas auf die Handyzeit stellen. Das ist ein SPRUNG - anders
     * als das Nachholen, das jede Sekunde durchlaufen laesst. Deshalb nur auf
     * ausdruecklichen Wunsch und mit Warnung.
     */
    private fun confirmSetClock() {
        val c = java.util.Calendar.getInstance()
        val h = c.get(java.util.Calendar.HOUR_OF_DAY)
        val m = c.get(java.util.Calendar.MINUTE)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_clock))
            .setMessage(getString(R.string.set_clock_confirm, String.format("%02d:%02d", h, m)))
            .setPositiveButton(getString(R.string.set_clock)) { _, _ ->
                val ok = EmuNative.setClock(h, m, c.get(java.util.Calendar.SECOND))
                toast(if (ok) getString(R.string.done) else getString(R.string.import_failed))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRestart() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.restart_confirm))
            .setPositiveButton(getString(R.string.restart)) { _, _ ->
                EmuNative.persist(true)
                if (EmuNative.isRunning()) EmuNative.resetRom()
                else EmuService.restartCore(this)
                toast(getString(R.string.done))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmWipe() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.wipe_confirm))
            .setPositiveButton(getString(R.string.wipe)) { _, _ ->
                EmuNative.stop(); EmuNative.unload()
                EmuFiles.sav(this, device).delete()
                EmuFiles.ram(this, device).delete()
                EmuFiles.state(this, device).delete()
                EmuService.reload(this)
                toast(getString(R.string.done))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Wirklich schliessen. Das Beenden muss AUS DEM DIENST kommen: wer nur den
     * Prozess abschiesst, waehrend der Dienst angemeldet ist, bekommt ihn von
     * Android sofort zurueck.
     */
    private fun confirmAppQuit() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_quit))
            .setMessage(getString(R.string.app_quit_msg))
            .setPositiveButton(getString(R.string.app_quit)) { _, _ ->
                EmuService.quit(this)
                finishAffinity()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAbout() {
        val ver = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: ""
        val pct = EmuNative.speedPercent()
        val speed = when {
            pct <= 0 -> ""
            pct >= 90 -> "\n\n" + getString(R.string.speed_ok, pct)
            else -> "\n\n" + getString(R.string.speed_slow, pct)
        }
        val tv = TextView(this).apply {
            text = getString(R.string.about_msg, ver) + speed
            setTextIsSelectable(true)
            autoLinkMask = android.text.util.Linkify.WEB_URLS
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val sc = ScrollView(this); sc.addView(tv)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about))
            .setView(sc)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showText(title: String, text: String) {
        val tv = TextView(this).apply {
            setText(text)
            setTextIsSelectable(true)
            textSize = 13f
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val sc = ScrollView(this); sc.addView(tv)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(sc)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return null
    }

    // --------------------------------------------------------- Beschriftungen

    private fun eggName(): String =
        resources.getStringArray(R.array.egg_colors)[eggColor.coerceIn(0, EggRenderer.EGG_COUNT - 1)]

    private fun rateLabel() = when (widgetMs) {
        500 -> getString(R.string.rate_fast)
        1000 -> getString(R.string.rate_mid)
        else -> getString(R.string.rate_eco)
    }

    private fun volLabel() = when (soundVol) {
        in 0..12 -> getString(R.string.vol_low)
        in 13..28 -> getString(R.string.vol_mid)
        else -> getString(R.string.vol_high)
    }

    private fun vibModeLabel() = when (vibMode) {
        0 -> getString(R.string.off)
        1 -> getString(R.string.vib_every_tone)
        else -> getString(R.string.vib_call_only)
    }

    private fun vibRepLabel() =
        if (vibRepeatMin <= 0) getString(R.string.off)
        else getString(R.string.minutes, vibRepeatMin)

    private fun expiryLabel() =
        if (callExpiryMin <= 0) getString(R.string.off)
        else getString(R.string.minutes, callExpiryMin)

    private fun catchLabel() =
        if (catchupMult < 2) getString(R.string.off) else "x$catchupMult"

    // --------------------------------------------------------------- Bausteine

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(res: Int) {
        box.addView(TextView(this).apply {
            setText(res)
            setTextColor(Color.parseColor("#3367D6"))
            textSize = 16f
            setPadding(0, dp(16), 0, dp(4))
        })
    }

    private fun hint(res: Int) = hint2(getString(res))

    private fun hint2(text: String) {
        box.addView(TextView(this).apply {
            setText(text)
            textSize = 12f
            setTextColor(Color.parseColor("#777777"))
        })
    }

    private fun button(res: Int, onClick: () -> Unit) {
        box.addView(Button(this).apply {
            setText(res)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        })
    }

    /** Knopf, dessen Beschriftung sich nach jedem Klick selbst aktualisiert. */
    private fun stateButton(labelFn: () -> String, onClick: () -> Unit): Button {
        lateinit var b: Button
        b = Button(this).apply {
            text = labelFn()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick(); b.text = labelFn() }
        }
        box.addView(b)
        return b
    }

    private fun onOff(b: Boolean) = if (b) getString(R.string.on) else getString(R.string.off)

    private fun nextIn(cur: Int, opts: IntArray): Int {
        val i = opts.indexOf(cur)
        return opts[if (i < 0) 0 else (i + 1) % opts.size]
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
