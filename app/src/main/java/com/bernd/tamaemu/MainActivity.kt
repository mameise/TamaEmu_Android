package com.bernd.tamaemu

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

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
        super.onCreate(s)
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
            ).apply { leftMargin = (8 * d).toInt(); rightMargin = (8 * d).toInt() }
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
                bottomMargin = (32 * d).toInt()
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

        btnRow = row
        root.addView(panel)
        root.addView(hint)
        root.addView(speedRow)
        root.addView(row)
        setContentView(root)
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

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

        // Wer die App oeffnet, will sie wieder laufen sehen.
        if (this.userQuit) this.userQuit = false
        if (EmuFiles.hasRom(this)) {
            if (this.bgRun) EmuService.start(this) else EmuService.bootCore(this)
        }
        speedRow.visibility = if (this.speedOnUi) View.VISIBLE else View.GONE
        btnRow.visibility = if (this.showButtons) View.VISIBLE else View.GONE
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

        override fun onDraw(canvas: Canvas) {
            val side = minOf(width, height)
            if (side <= 0) return
            var s = side / EmuNative.W
            if (s < 1) s = 1
            val d = EmuNative.W * s
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
