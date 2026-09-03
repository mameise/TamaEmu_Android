package com.bernd.tamaemu

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Vollbild-App. Zeigt das 128x128-Panel gross und reicht A/B/C durch; der
 * Emulator selbst laeuft im nativen Thread weiter, auch wenn die App zu ist.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val px = IntArray(EmuNative.W * EmuNative.H)
    private var lastFrame = 0L
    private var running = false
    private lateinit var panel: PanelView
    private lateinit var hint: TextView
    private lateinit var speedRow: LinearLayout
    private lateinit var btnRow: LinearLayout
    private lateinit var gearRow: LinearLayout
    private lateinit var speedLabel: TextView
    private var btnH = 0

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            if (EmuFiles.hasRom(this@MainActivity) && EmuNative.isLoaded()) {
                val n = EmuNative.frame(px)
                if (n != lastFrame) {
                    lastFrame = n
                    EggRenderer.pushPixels(px)
                    panel.invalidate()
                }
                hint.visibility = View.GONE
                panel.visibility = View.VISIBLE
            } else {
                hint.visibility = View.VISIBLE
                panel.visibility = View.GONE
            }
            ui.postDelayed(this, 33)   // ~30 fps Anzeige
        }
    }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate(s: Bundle?) {
        /*
         * Vollbild: ohne Titelleiste und ohne Systemleisten. Das muss VOR
         * setContentView geschehen, sonst ist der Titel schon angelegt.
         * Umschalten laeuft ueber recreate(), wie bei der Sprache.
         */
        if (this.fullscreen) {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            actionBar?.hide()
        }
        super.onCreate(s)
        if (this.fullscreen) hideSystemBars()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0x18, 0x1A, 0x1C))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        panel = PanelView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        hint = TextView(this).apply {
            text = getString(R.string.import_hint)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setOnClickListener { openSettings() }
        }

        val d = resources.displayMetrics.density
        btnH = (64 * d).toInt()
        // Tempo-Knoepfe, wahlweise ueber den A/B/C-Knoepfen
        speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = (8 * d).toInt(); rightMargin = (8 * d).toInt()
                bottomMargin = (12 * d).toInt()   // sonst klebt es an A/B/C
            }
        }
        speedLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f)
        }
        speedRow.addView(speedButton("\u2212") { setSpeed(EmuNative.speedStep(-1)) })
        speedRow.addView(speedLabel)
        speedRow.addView(speedButton("+") { setSpeed(EmuNative.speedStep(+1)) })
        speedRow.addView(speedButton("x1") { EmuNative.setSpeed(1); setSpeed(1) })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (if (this@MainActivity.fullscreen) 8 else 32) * d.toInt()
                leftMargin = (8 * d).toInt(); rightMargin = (8 * d).toInt()
            }
        }
        row.addView(holdButton("A", EmuNative.BTN_A))
        row.addView(holdButton("B", EmuNative.BTN_B))
        row.addView(holdButton("C", EmuNative.BTN_C))
        row.addView(Button(this).apply {
            text = "\u2699"   // Zahnrad
            layoutParams = LinearLayout.LayoutParams(0, btnH, 0.6f)
            setOnClickListener { openSettings() }
        })

        /*
         * Zahnrad fuer den Fall, dass die Knopfreihe ausgeblendet ist (reine
         * Gamepad-Bedienung). Dezent unten rechts, damit es nicht stoert -
         * aber gross genug zum Treffen. Ohne das kaeme man nicht mehr in die
         * Einstellungen und muesste die App neu installieren.
         */
        gearRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = (12 * d).toInt(); bottomMargin = (16 * d).toInt()
            }
            addView(TextView(this@MainActivity).apply {
                text = "\u2699"
                textSize = 22f
                setTextColor(Color.parseColor("#77FFFFFF"))
                setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (6 * d).toInt())
                setOnClickListener { openSettings() }
            })
        }

        btnRow = row
        root.addView(panel)
        root.addView(hint)
        root.addView(speedRow)
        root.addView(row)
        root.addView(gearRow)
        setContentView(root)
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    /** Statusleiste und Navigationsleiste ausblenden, Wischen holt sie kurz zurueck. */
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    private fun speedButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(0, (44 * resources.displayMetrics.density).toInt(), 1f)
        setOnClickListener { onClick() }
    }

    private fun setSpeed(m: Int) {
        this.speed = m
        speedLabel.text = getString(R.string.speed_lbl, m)
    }

    /**
     * Gamepad: die Tastencodes kommen aus den Einstellungen. Gedrueckt halten
     * wird durchgereicht wie bei den Schaltflaechen, damit die Entprellung der
     * Firmware den Druck sieht.
     */
    override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean {
        if (!this.gamepad) return super.dispatchKeyEvent(e)
        val maske = when (e.keyCode) {
            this.padA -> EmuNative.BTN_A
            this.padB -> EmuNative.BTN_B
            this.padC -> EmuNative.BTN_C
            else -> 0
        }
        if (maske == 0) return super.dispatchKeyEvent(e)
        when (e.action) {
            android.view.KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) Input.down(maske)
            android.view.KeyEvent.ACTION_UP -> Input.up(maske)
        }
        return true
    }

    private fun holdButton(label: String, mask: Int): Button = Button(this).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(0, btnH, 1f)
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { Input.down(mask); v.isPressed = true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Input.up(mask); v.isPressed = false
                }
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)

        if (EmuNative.isLoaded() && EmuNative.saveMismatch())
            Toast.makeText(this, R.string.save_mismatch, Toast.LENGTH_LONG).show()

        // Wer die App oeffnet, will sie wieder laufen sehen.
        if (this.userQuit) this.userQuit = false
        if (EmuFiles.hasRom(this)) {
            if (this.bgRun) EmuService.start(this) else EmuService.bootCore(this)
        }
        panel.fillMode = this.fullscreen && !this.sharpPixels
        if (this.fullscreen) hideSystemBars()
        speedRow.visibility = if (this.speedOnUi) View.VISIBLE else View.GONE
        btnRow.visibility = if (this.showButtons) View.VISIBLE else View.GONE
        // Das Zahnrad braucht es nur, wenn die Knopfreihe (mit ihrem eigenen
        // Zahnrad) nicht da ist.
        gearRow.visibility = if (this.showButtons) View.GONE else View.VISIBLE
        setSpeed(this.speed)

        running = true
        ui.post(loop)
    }

    override fun onPause() {
        super.onPause()
        running = false
        ui.removeCallbacks(loop)
        Input.up(7)
        if (EmuNative.isLoaded()) {
            EmuNative.persist(false)
            EmuWidgetProvider.renderWidgets(this, force = true)
        }
    }

    /** Zeichnet das Panel zentriert und ganzzahlig skaliert (scharfe Pixel). */
    class PanelView(ctx: android.content.Context) : View(ctx) {
        private val dst = Rect()
        private val bezel = Paint().apply { color = Color.rgb(0x2E, 0x31, 0x2E) }

        /**
         * Im Vollbild wird die Flaeche ausgenutzt, sonst ganzzahlig skaliert.
         *
         * Ganzzahlig haelt die Pixel gleich gross und scharf, verschenkt aber
         * bis zur naechsten Stufe viel Platz - auf einem 640x480-Handheld
         * waeren das 384 statt 480 Punkten. Das Seitenverhaeltnis bleibt in
         * beiden Faellen quadratisch, es wird also nie verzerrt.
         */
        var fillMode = false

        override fun onDraw(canvas: Canvas) {
            val side = minOf(width, height)
            if (side <= 0) return
            var s = side / EmuNative.W
            if (s < 1) s = 1
            var d = EmuNative.W * s
            if (fillMode) d = side
            val left = (width - d) / 2
            val top = (height - d) / 2
            canvas.drawRect(
                (left - 6).toFloat(), (top - 6).toFloat(),
                (left + d + 6).toFloat(), (top + d + 6).toFloat(), bezel
            )
            dst.set(left, top, left + d, top + d)
            EggRenderer.drawScreen(canvas, dst)
        }
    }
}
