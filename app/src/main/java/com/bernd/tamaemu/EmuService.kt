package com.bernd.tamaemu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haelt den Emulator am Leben, auch wenn die App weggewischt wird, und schiebt
 * dem Ei-Widget frische Bilder. Der Emulator selbst laeuft in seinem eigenen
 * nativen Thread (native-emu.c) mit 1/60-s-Deadline-Taktung; dieser Dienst
 * sorgt nur dafuer, dass der Prozess lebt, und uebernimmt Ton und Vibration.
 */
class EmuService : Service() {

    companion object {
        const val CH_ID = "tamaemu_live"
        const val CH_CALL = "tamaemu_call"
        const val NOTIF_ID = 42
        const val NOTIF_CALL_ID = 43
        const val ACTION_STOP = "com.bernd.tamaemu.STOP_SERVICE"
        const val ACTION_RELOAD = "com.bernd.tamaemu.RELOAD"
        const val ACTION_RESTART = "com.bernd.tamaemu.RESTART"
        const val ACTION_QUIT = "com.bernd.tamaemu.QUIT"

        /*
         * Waehrend die Oberflaeche am Kern arbeitet (Zusatzinhalte einspielen,
         * Platz freigeben, Spielstand einlesen oder loeschen), darf der Takt
         * des Dienstes NICHT dazwischenfunken. Er ruft sonst mitten im
         * Entladen bootCore auf - dann laufen zwei Faeden gegeneinander, und
         * hinterher haengt die Tonerzeugung an einem Kern, den es nicht mehr
         * gibt. Genau daran war der Ton nach einem DLC-Eingriff weg.
         */
        @Volatile var coreBusy = false

        /** Arbeit am Kern, waehrend der Takt pausiert. */
        fun <T> withCore(block: () -> T): T {
            coreBusy = true
            try { return block() } finally { coreBusy = false }
        }

        @Volatile var running = false; private set

        fun start(ctx: Context) {
            if (!EmuFiles.hasRom(ctx)) return
            if (ctx.userQuit) return          // bewusst geschlossen - nicht wiederbeleben
            val i = Intent(ctx, EmuService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Throwable) { /* Hintergrundstart ggf. blockiert */ }
        }

        fun stop(ctx: Context) {
            runCatching {
                ctx.startService(Intent(ctx, EmuService::class.java).setAction(ACTION_STOP))
            }
        }

        /** Alles beenden und den Prozess verlassen. */
        fun quit(ctx: Context) {
            ctx.userQuit = true
            runCatching {
                ctx.startService(Intent(ctx, EmuService::class.java).setAction(ACTION_QUIT))
            }.onFailure {
                // Dienst laeuft nicht mehr: dann eben von hier aus.
                EmuNative.persist(true)
                EmuNative.stop()
                EmuNative.unload()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }

        /** Geraet oder Firmware gewechselt: Kern entladen und frisch hochfahren. */
        fun reload(ctx: Context) {
            val i = Intent(ctx, EmuService::class.java).setAction(ACTION_RELOAD)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Throwable) {
                // Dienst laeuft nicht und darf nicht starten: dann direkt hier neu laden.
                bootCore(ctx)
            }
        }

        /** ROM + Spielstand laden und den Emulator-Thread starten. */
        fun bootCore(ctx: Context): Boolean {
            if (!EmuFiles.hasRom(ctx)) return false
            if (!EmuNative.isLoaded()) {
                val dev = ctx.device
                val err = EmuNative.init(
                    EmuFiles.rom(ctx).absolutePath, dev,
                    EmuFiles.sav(ctx, dev).absolutePath,
                    EmuFiles.ram(ctx, dev).absolutePath,
                    EmuFiles.state(ctx, dev).absolutePath,
                    // Der Aufbau der Emu-Struktur kann sich zwischen Fassungen
                    // aendern, deshalb haengt der Zustand am Build.
                    BuildConfig.BUILD_TAG + "." + BuildConfig.VERSION_CODE
                )
                if (err.isNotEmpty()) return false
            }
            EmuNative.setSpeed(ctx.speed)
            EmuNative.setStayAwake(ctx.stayAwake)
            EmuNative.linkLead(ctx.linkLeadUs)
            EmuNative.callHookSet(ctx.callHooksFor(ctx.device))
            EmuNative.callMelody(true)
            EmuNative.callExpiry(ctx.callExpiryMin * 60)
            if (!EmuNative.isRunning()) {
                EmuNative.audioReset()      // Ring und Zeitmarken passen sonst nicht
                if (!EmuNative.start()) {
                    /* Kern angehalten (etwa falsches Geraeteprofil zur
                     * Firmware): entladen, damit der naechste Versuch sauber
                     * neu aufsetzt statt still schwarz zu bleiben. */
                    android.util.Log.e("tamaemu", "[dienst] Kern startet nicht - wird entladen")
                    EmuNative.unload()
                    return false
                }
                startCatchUp(ctx)
            }
            return true
        }

        /**
         * Zeit nachholen, die der Prozess tot war. NICHT die Uhr stellen: die
         * Spieluhr laeuft nur schneller, bis die Luecke zu ist, damit die
         * Firmware jede Sekunde zu sehen bekommt und keine Aktion ausfaellt.
         * Gedeckelt auf 24 h - was laenger her ist, wird nicht nachgeholt.
         */
        private fun startCatchUp(ctx: Context) {
            val mult = ctx.catchupMult
            val last = ctx.lastWallMs
            ctx.lastWallMs = System.currentTimeMillis()
            if (mult < 2 || last <= 0L) return
            val gapMs = System.currentTimeMillis() - last
            if (gapMs < 60_000L) return
            val secs = (gapMs / 1000.0).coerceAtMost(24 * 3600.0)
            EmuNative.catchUp(secs, mult)
        }

        /**
         * Kern vollstaendig neu hochfahren. Das MUSS ueber den Dienst laufen:
         * frueher geschah es direkt im Bedienfaden, waehrend der Takt des
         * Dienstes alle 500 ms selbst bootCore aufrief - die beiden kamen sich
         * ins Gehege, und der Kern blieb halb entladen zurueck. Deshalb jetzt
         * eine Nachricht an den Dienst, der es in SEINEM Faden erledigt.
         */
        fun restartCore(ctx: Context) {
            val i = Intent(ctx, EmuService::class.java).setAction(ACTION_RESTART)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Throwable) {
                EmuNative.stop(); EmuNative.unload(); bootCore(ctx)
            }
        }

        /** Fuer Widget-Taps aus kaltem Prozess: kurz warten, bis der Kern steht. */
        fun waitWarm(ctx: Context, ms: Long): Boolean {
            if (bootCore(ctx) && EmuNative.isLoaded()) return true
            val until = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < until) {
                if (EmuNative.isLoaded()) return true
                Thread.sleep(50)
            }
            return EmuNative.isLoaded()
        }
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var sndThread: Thread? = null
    @Volatile private var sndRun = false
    @Volatile private var lastVibeAt = 0L
    @Volatile private var flagCalling = false
    @Volatile private var gesehenerDruck = 0L

    private val tick = object : Runnable {
        override fun run() {
            val ctx = applicationContext
            if (coreBusy) {                       // Oberflaeche arbeitet gerade
                handler.postDelayed(this, 100L)   // zuegig wieder nachsehen
                return
            }
            if (!bootCore(ctx)) { stopSelf(); return }
            EmuWidgetProvider.renderWidgets(ctx)
            ctx.lastWallMs = System.currentTimeMillis()
            checkCall(ctx)
            maybeRepeatVibe(ctx)
            handler.postDelayed(this, ctx.widgetMs.toLong().coerceAtLeast(250L))
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        /* Zwischenspeicher aufraeumen: Reste vom Import oder von Zusatzinhalten
         * werden nicht mehr gebraucht und wachsen sonst mit. */
        runCatching {
            java.io.File(cacheDir, "import.bin").delete()
            java.io.File(cacheDir, "dlc").listFiles()?.forEach { it.delete() }
        }
        running = true
        makeChannel()
        startForeground(NOTIF_ID, note())
        thread = HandlerThread("tamaemu-svc").also { it.start() }
        handler = Handler(thread.looper)
        handler.post(tick)
        startSound()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::handler.isInitialized) return START_STICKY   // onCreate war noch nicht dran
        when (intent?.action) {
            ACTION_STOP -> {
                abbauen()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_QUIT -> {
                abbauen()
                stopSelf()
                /* Erst wenn der Dienst wirklich abgemeldet ist, den Prozess
                 * verlassen - sonst startet Android ihn wieder. */
                Handler(Looper.getMainLooper()).postDelayed({
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 400)
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> handler.post {
                EmuNative.stop()
                EmuNative.unload()
                if (!coreBusy) {          // waehrend eines Imports NICHT starten
                    bootCore(applicationContext)
                    startSound()          // falls der Tonfaden zwischendurch endete
                }
            }
            ACTION_RESTART -> handler.post {
                EmuNative.persist(true)
                EmuNative.stop()
                EmuNative.unload()
                val ok = bootCore(applicationContext)
                android.util.Log.i("tamaemu", "[dienst] Kern neu gestartet: $ok")
            }
        }
        return START_STICKY
    }

    /**
     * Vollstaendiger Abbau: sichern, Kern anhalten und ENTLADEN, Ton aus,
     * Meldungen weg, Widget auf "nicht geladen". Frueher wurde hier nur
     * gespeichert und der Dienst gestoppt - der Kern blieb geladen im Speicher
     * liegen, und mit START_STICKY holte Android den Dienst gleich zurueck.
     */
    private fun abbauen() {
        runCatching {
            VisitBridge.stop()
            sndRun = false
            if (::handler.isInitialized) handler.removeCallbacksAndMessages(null)
            EmuNative.persist(true)
            EmuNative.stop()
            EmuNative.unload()
            clearCallNotice()
            EmuWidgetProvider.renderWidgets(applicationContext, force = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {
        sndRun = false
        if (::handler.isInitialized) handler.removeCallbacksAndMessages(null)
        if (::thread.isInitialized) thread.quitSafely()
        EmuNative.persist(true)
        EmuNative.stop()
        applicationContext.lastWallMs = System.currentTimeMillis()
        running = false
        super.onDestroy()
    }

    // ------------------------------------------------------------------- Ton
    /**
     * Ein Thread pollt den Tonzustand des Kerns (~10 ms) UND erzeugt die
     * Samples; AudioTrack.write blockiert bufferweise und taktet die Schleife.
     * Aus dem Piezo kommt ein Rechteck, kein Sinus.
     */
    /**
     * Legt die Tonausgabe an. Als eigene Funktion, damit sie sich nach einem
     * Fehler neu aufbauen laesst - der Puffer fasst acht Bloecke, weil der
     * Emulator in Schueben von 1/60 s liefert und die Ausgabe alle 10 ms holt.
     */
    private fun baueTrack(sr: Int, blk: Int): AudioTrack? = runCatching {
        val min = AudioTrack.getMinBufferSize(
            sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, blk * 2 * 8))
            .build()
    }.getOrNull()

    private fun startSound() {
        if (sndRun) return
        sndRun = true
        val ctx = applicationContext
        sndThread = Thread {
            val sr = 48000                    // wie in der Desktop-Fassung
            val blk = 480                     // 10 ms je Durchgang
            var track = baueTrack(sr, blk) ?: run { sndRun = false; return@Thread }
            var fehler = 0
            EmuNative.audioStart(sr)
            // Vorrat sammeln, bevor die Ausgabe anlaeuft: rund 150 ms. Weniger
            // hiess, dass jeder Schub der Emulation knapp reichte.
            var wait = 0
            while (sndRun && EmuNative.audioAvail() < sr / 7 && wait < 60) {
                Thread.sleep(20); wait++
            }
            track.play()
            val buf = ShortArray(blk)
            var prevTone = 0
            var vol = -1

            while (sndRun) {
                if (!EmuNative.isLoaded()) { Thread.sleep(50); continue }

                // Lautstaerke nur bei Aenderung durchreichen
                val v = if (ctx.sound) ctx.soundVol.coerceIn(0, 100) else 0
                if (v != vol) { EmuNative.audioVolume(v); vol = v }

                // Vibration bei JEDEM Ton bleibt am Zustand haengen; der Ruf
                // laeuft ueber checkCall.
                if (ctx.vibMode == 1) {
                    val f = EmuNative.tone()
                    if (f != 0 && prevTone == 0) vibrate(ctx.vibeMs.toLong())
                    prevTone = f
                }

                // Lieber zwei Millisekunden warten als einen halben Block
                // ziehen - ein angebrochener Block zerschneidet den Ton.
                var tries = 0
                while (sndRun && EmuNative.audioAvail() < blk && tries < 10) {
                    Thread.sleep(2); tries++
                }
                EmuNative.audioPull(buf, blk)

                /*
                 * Schreiben kann fehlschlagen, ohne zu werfen: AudioTrack gibt
                 * dann einen negativen Fehlerwert zurueck. Frueher lief die
                 * Schleife danach ewig weiter und schrieb ins Leere - der Ton
                 * war weg, bis der Prozess neu startete. Jetzt wird die Ausgabe
                 * neu aufgebaut.
                 */
                val n = try { track.write(buf, 0, blk) } catch (_: Throwable) { -1 }
                if (n < 0) {
                    fehler++
                    android.util.Log.w("tamaemu", "[ton] Ausgabe meldet $n, baue neu ($fehler)")
                    runCatching { track.stop() }
                    runCatching { track.release() }
                    if (fehler > 5) break
                    track = baueTrack(sr, blk) ?: break
                    EmuNative.audioStart(sr)
                    track.play()
                    vol = -1
                } else if (fehler > 0 && n == blk) {
                    fehler = 0
                }
            }
            runCatching { track.stop() }
            runCatching { track.release() }
            // Wichtig: freigeben, damit startSound() den Faden wieder anlegen
            // kann. Frueher blieb sndRun auf true stehen und der Ton war
            // dauerhaft verloren.
            sndRun = false
        }.also { it.isDaemon = true; it.start() }
    }

    /**
     * Ruf ueber die gemessene Rufmelodie bzw. die Ruf-Haken im Kern. Ein
     * Tastendruck kurz zuvor bedeutet: es wurde gepflegt, also abhaken.
     */
    private fun checkCall(ctx: Context) {
        val now = System.currentTimeMillis()

        /*
         * Wer eine Taste drueckt, kuemmert sich - also Ruf abhaken UND die
         * Meldung wegnehmen. Frueher fehlte hier das Wegnehmen, und der
         * Abbruch mit return sprang ueber die Stelle hinweg, an der es sonst
         * passiert waere: die Meldung blieb stehen, obwohl gefuettert war.
         *
         * Verglichen wird der Zeitstempel des letzten Drucks mit dem zuletzt
         * gesehenen, nicht mit einem Zeitfenster. Sonst geht der Druck
         * verloren, wenn der Takt gerade auf "sparsam" (2 s) steht.
         */
        val presse = Input.lastPressMs
        if (presse != gesehenerDruck) {
            gesehenerDruck = presse
            if (EmuNative.callState() == 1) {
                EmuNative.callClear()
                flagCalling = false
                clearCallNotice()
                return
            }
        }
        val calls = EmuNative.callState() == 1
        if (calls && !flagCalling) {
            flagCalling = true
            if (ctx.vibMode == 2) {
                vibrate(ctx.vibeMs.toLong())
                lastVibeAt = now
            }
            if (ctx.notifyCall) postCallNotice()
        } else if (!calls) {
            /* Auch wenn der Ruf von selbst verfallen ist: Meldung weg. */
            if (flagCalling) clearCallNotice()
            flagCalling = false
        }
    }

    /** Es ruft gerade. */
    private fun calling(): Boolean = flagCalling

    /** Solange der Ruf anhaelt, im eingestellten Takt erinnern. */
    private fun maybeRepeatVibe(ctx: Context) {
        if (ctx.vibMode != 2 || !calling()) return
        val rep = ctx.vibRepeatMin
        if (rep <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastVibeAt >= rep * 60_000L) {
            vibrate(ctx.vibeMs.toLong())
            lastVibeAt = now
        }
    }

    /** Eigene Meldung "braucht Aufmerksamkeit", getrennt von der Dienstzeile. */
    private fun postCallNotice() {
        runCatching {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val open = PendingIntent.getActivity(
                this, 2, Intent(this, MainActivity::class.java), flags
            )
            val n = Notification.Builder(this, CH_CALL)
                .setContentTitle(getString(R.string.call_notice_title))
                .setContentText(getString(R.string.call_notice_text))
                .setSmallIcon(R.drawable.ic_stat_egg)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_CALL_ID, n)
        }
    }

    private fun clearCallNotice() {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIF_CALL_ID)
        }
    }

    private fun vibrate(ms: Long) {
        runCatching {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // ---------------------------------------------------------- Notification

    private fun makeChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CH_ID, "TamaEmu", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            mgr.createNotificationChannel(ch)
            // Eigener Kanal fuer den Ruf: laut genug, um aufzufallen, und in den
            // Systemeinstellungen getrennt regelbar.
            val cc = NotificationChannel(
                CH_CALL, getString(R.string.call_notice_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            cc.description = getString(R.string.call_notice_text)
            mgr.createNotificationChannel(cc)
        }
    }

    private fun note(): Notification {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        // Die Aktion in der Meldung schliesst die App ganz - das ist es, was man
        // von einem Knopf in der Benachrichtigung erwartet.
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EmuService::class.java).setAction(ACTION_QUIT), flags
        )
        return Notification.Builder(this, CH_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_running))
            .setSmallIcon(R.drawable.ic_stat_egg)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?, getString(R.string.app_quit), stop
                ).build()
            )
            .setOngoing(true)
            .build()
    }
}
