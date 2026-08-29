package com.bernd.tamaemu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Zeichnet das Tama im Ei in der TATSAECHLICHEN Widget-Groesse (w x h px),
 * damit nichts verzerrt und die Pixel scharf bleiben. Geometrie und Palette
 * sind aus dem Tamago-Ei-Widget uebernommen; hier sitzt statt des 48x31-LCD
 * das quadratische 128x128-Farbpanel des S1C33.
 * Die A/B/C-Tap-Flaechen liegen als unsichtbare Views darueber (widget_egg.xml).
 */
object EggRenderer {

    val EGG_SHELL = intArrayOf(
        Color.rgb(0x4F, 0xB6, 0xAC),  // 0 teal
        Color.rgb(0xEC, 0x9A, 0xB6),  // 1 pink
        Color.rgb(0x8B, 0xC3, 0x6B),  // 2 gruen
        Color.rgb(0x6E, 0x9B, 0xE0),  // 3 blau
        Color.rgb(0xE6, 0xC2, 0x5A),  // 4 gelb
        Color.rgb(0xB0, 0x8B, 0xD8),  // 5 lila
        Color.rgb(0xE8, 0x8A, 0x5A),  // 6 orange
        Color.rgb(0x9A, 0xA0, 0xA6)   // 7 grau
    )
    val EGG_COUNT = EGG_SHELL.size

    val EGG_NAMES = arrayOf(
        "Tuerkis", "Rosa", "Gruen", "Blau", "Gelb", "Lila", "Orange", "Grau"
    )

    private val screenBmp: Bitmap =
        Bitmap.createBitmap(EmuNative.W, EmuNative.H, Bitmap.Config.ARGB_8888)
    private val src = Rect(0, 0, EmuNative.W, EmuNative.H)
    private val flat = Paint().apply { isFilterBitmap = false; isAntiAlias = false }

    private fun blend(a: Int, b: Int, num: Int, den: Int): Int {
        if (den <= 0) return a
        val n = num.coerceIn(0, den)
        fun c(ca: Int, cb: Int) = ca + (cb - ca) * n / den
        return Color.rgb(
            c(Color.red(a), Color.red(b)),
            c(Color.green(a), Color.green(b)),
            c(Color.blue(a), Color.blue(b))
        )
    }

    /** Panel-Pixel in das gemeinsame Bitmap uebernehmen. */
    @Synchronized
    fun pushPixels(px: IntArray) {
        screenBmp.setPixels(px, 0, EmuNative.W, 0, 0, EmuNative.W, EmuNative.H)
    }

    /**
     * Nur der Bildschirm, mittig und ganzzahlig hochskaliert, auf durchsichtigem
     * Grund - fuer das schlichte Widget ohne Ei. Die Knoepfe sind dort echte
     * Schaltflaechen im Layout und nicht gezeichnet.
     */
    @Synchronized
    fun renderPlain(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(maxOf(w, 8), maxOf(h, 8), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val side = minOf(bmp.width, bmp.height)
        val fr = maxOf(2f, side * 0.03f)
        val frei = side - 2 * fr
        /*
         * Ganzzahlig skalieren haelt die Pixel gleich gross und scharf. Der
         * Sprung von 1x auf 2x laesst aber bei mittleren Widget-Groessen viel
         * Flaeche leer - bei 220 px waeren es nur 128 statt moeglicher 200.
         * Deshalb: ganzzahlig bevorzugen, aber wenn dabei mehr als ein Viertel
         * der Flaeche verschenkt wird, lieber genau ausfuellen.
         */
        var s = (frei / EmuNative.W).toInt()
        if (s < 1) s = 1
        var d = EmuNative.W * s
        if (d < frei * 0.75f) d = frei.toInt()
        val left = (bmp.width - d) / 2
        val top = (bmp.height - d) / 2
        p.color = Color.rgb(0x2E, 0x31, 0x2E)
        cv.drawRoundRect(
            RectF(left - fr, top - fr, left + d + fr, top + d + fr), fr, fr, p
        )
        dst2.set(left, top, left + d, top + d)
        cv.drawBitmap(screenBmp, src, dst2, flat)
        return bmp
    }

    private val dst2 = Rect()

    /** Nur der Bildschirm, ganzzahlig hochskaliert (Vollbild-App). */
    @Synchronized
    fun drawScreen(cv: Canvas, dst: Rect) {
        cv.drawBitmap(screenBmp, src, dst, flat)
    }

    /**
     * @param big  groessere Bildflaeche: das Ei wird flacher gezeichnet, der
     *             Schirm nimmt mehr Platz, die Knoepfe ruecken tiefer und
     *             werden kleiner. Fuer die grosse Widget-Ausfuehrung.
     */
    /** Ergebnis der Geometrieberechnung: alles liegt garantiert im Ei. */
    private class Geo(
        val cx: Float, val cy: Float, val a: Float, val b: Float,
        val side: Float, val ys: Float,
        val yb: Float, val br: Float, val dx: Float, val frame: Float
    )

    /**
     * Groesster Schirm, der samt Knoepfen INNERHALB der Eiflaeche bleibt.
     *
     * Feste Prozentwerte gehen hier schief: oben und unten wird die Ellipse
     * schmal, ein Rechteck mit fester Breite ragt dort heraus. Deshalb wird die
     * Seitenlaenge eingeschachtelt und jedes Mal geprueft, ob die Ecken noch in
     * der Ellipse liegen - mitsamt Rahmen und etwas Sicherheitsabstand.
     */
    private fun geo(w: Int, h: Int, big: Boolean): Geo {
        val mn = minOf(w, h).toFloat()
        val m = mn * 0.03f
        val cx = w / 2f
        val cy = h / 2f
        val a = w / 2f - m
        val b = h / 2f - m

        fun halfWidth(y: Float): Float {
            val t = (y - cy) / b
            return if (abs(t) >= 1f) 0f else (a * sqrt(1f - t * t))
        }

        // Knopfreihe: Hoehe fest je Ausfuehrung, Radius aus der dortigen Eibreite
        val yb = cy + b * (if (big) 0.76f else 0.62f)
        val hw = halfWidth(yb)
        var br = minOf(mn * (if (big) 0.070f else 0.085f), hw * 0.30f)

        // Ein Kreis liegt nur dann im Ei, wenn sein ganzer Rand drinliegt -
        // die halbe Breite auf Mittelhoehe zu pruefen genuegt nicht.
        fun circleInside(bx: Float, by: Float, r: Float): Boolean {
            for (k in 0 until 24) {
                val ang = k * PI.toFloat() / 12f
                val x = bx + r * cos(ang)
                val y = by + r * sin(ang)
                val fa = a * 0.99f
                val fb = b * 0.99f
                if ((x - cx) * (x - cx) / (fa * fa) + (y - cy) * (y - cy) / (fb * fb) > 1f)
                    return false
            }
            return true
        }

        var dx = hw * 0.60f
        // Der mittlere Knopf sitzt tiefer, die aeusseren weiter aussen -
        // beides wird geprueft, notfalls schrumpft der Radius.
        for (n in 0 until 60) {
            dx = hw * 0.60f
            while (dx > 1f && !circleInside(cx + dx, yb, br)) dx -= 1f
            if (circleInside(cx, yb + br * 0.85f, br) && dx >= 2.2f * br) break
            br *= 0.95f
        }

        val frame = mn * 0.02f
        val safe = 0.94f
        val topLimit = cy - b + mn * (if (big) 0.02f else 0.04f)
        val botLimit = yb - br - mn * 0.05f

        fun place(side: Float): Float? {
            val ycs = minOf(cy, botLimit - side / 2f)
            val top = ycs - side / 2f
            val bot = ycs + side / 2f
            if (top < topLimit || bot > botLimit) return null
            val far = maxOf(abs(top - cy), abs(bot - cy)) + frame
            val half = side / 2f + frame
            val fa = a * safe
            val fb = b * safe
            if (half * half / (fa * fa) + far * far / (fb * fb) > 1f) return null
            return ycs
        }

        var lo = 8f
        var hi = minOf(2 * a, 2 * b)
        var bestSide = 8f
        var bestY = cy
        repeat(40) {
            val mid = (lo + hi) / 2f
            val y = place(mid)
            if (y == null) hi = mid else { bestSide = mid; bestY = y; lo = mid }
        }
        return Geo(cx, cy, a, b, bestSide, bestY, yb, br, dx, frame)
    }

    /*
     * ---------------------------------------------------------------------
     * Eigenes Widget-Bild
     *
     * Wer sein eigenes Gehaeuse zeichnen will, braucht feste Bezugspunkte.
     * Deshalb liegen Bildschirm und Knoepfe beim eigenen Bild NICHT dort, wo
     * die Ei-Rechnung sie hinlegt, sondern an festen Anteilen der Flaeche -
     * dieselben, die auch die Vorlage zeigt und die die unsichtbaren
     * Tap-Flaechen im Layout benutzen.
     *
     * Das Bild wird auf die Widget-Groesse GEZOGEN (nicht beschnitten), damit
     * die Anteile immer stimmen. Ein Bild in anderem Seitenverhaeltnis wird
     * also verzerrt - deshalb sind die Vorlagen quadratisch.
     */
    private fun schirmAnteil(big: Boolean) =
        if (big) floatArrayOf(0.05f, 0.72f) else floatArrayOf(0.07f, 0.64f)

    /** Bildschirmfeld in Pixeln fuer ein eigenes Bild. */
    fun skinScreenRect(w: Int, h: Int, big: Boolean): Rect {
        val a = schirmAnteil(big)
        val oben = h * a[0]
        val unten = h * a[1]
        val seite = minOf(w * (if (big) 0.90f else 0.84f), unten - oben)
        val left = ((w - seite) / 2f).toInt()
        val top = (oben + (unten - oben - seite) / 2f).toInt()
        return Rect(left, top, left + seite.toInt(), top + seite.toInt())
    }

    /** Mitten und Radius der drei Knoepfe fuer die Vorlage. */
    fun skinButtons(w: Int, h: Int, big: Boolean): Triple<FloatArray, FloatArray, Float> {
        /* Bei 0.885 stiess der untere Knopfrand in der grossen Ausfuehrung an
         * die Bildkante (99 %). 0.86 laesst Luft, bleibt aber innerhalb des
         * Tap-Bandes im Layout (78..99 %). */
        val yBand = if (big) 0.86f else 0.805f
        val r = minOf(w, h) * (if (big) 0.075f else 0.085f)
        val xs = floatArrayOf(w / 6f, w / 2f, w * 5f / 6f)
        val ys = floatArrayOf(h * yBand, h * yBand + r * 0.5f, h * yBand)
        return Triple(xs, ys, r)
    }

    /** Eigenes Bild, falls der Benutzer eines hinterlegt hat. */
    private fun skinBitmap(ctx: Context, big: Boolean): Bitmap? {
        val f = EmuFiles.skin(ctx, big)
        if (!f.exists()) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    @Synchronized
    fun renderSkin(ctx: Context, w: Int, h: Int, big: Boolean): Bitmap? {
        val skin = skinBitmap(ctx, big) ?: return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val glatt = Paint(Paint.FILTER_BITMAP_FLAG)
        cv.drawBitmap(skin, Rect(0, 0, skin.width, skin.height), Rect(0, 0, w, h), glatt)
        val r = skinScreenRect(w, h, big)
        cv.drawBitmap(screenBmp, src, r, flat)
        return bmp
    }

    /**
     * @param big  groessere Bildflaeche: die Knoepfe ruecken tiefer und werden
     *             kleiner, dadurch bleibt mehr Platz fuer den Schirm.
     */
    @Synchronized
    fun renderEgg(ctx: Context, w: Int, h: Int, big: Boolean = false): Bitmap {
        renderSkin(ctx, w, h, big)?.let { return it }   // eigenes Bild geht vor
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val shell = EGG_SHELL[ctx.eggColor.coerceIn(0, EGG_SHELL.size - 1)]
        val mn = minOf(w, h).toFloat()
        val g = geo(w, h, big)

        // Ei-Koerper
        val body = RectF(g.cx - g.a, g.cy - g.b, g.cx + g.a, g.cy + g.b)
        p.color = shell
        cv.drawOval(body, p)
        // Glanz oben, innerhalb der Kuppe
        p.color = blend(shell, Color.WHITE, 42, 100)
        cv.drawOval(
            RectF(g.cx - g.a * 0.34f, g.cy - g.b * 0.90f,
                  g.cx + g.a * 0.34f, g.cy - g.b * 0.68f), p
        )
        // Rand
        p.style = Paint.Style.STROKE
        p.strokeWidth = maxOf(2f, mn * 0.012f)
        p.color = blend(shell, Color.BLACK, 30, 100)
        cv.drawOval(body, p)
        p.style = Paint.Style.FILL

        // Schirm mit Rahmen
        val s = g.side
        val bez = RectF(g.cx - s / 2f - g.frame, g.ys - s / 2f - g.frame,
                        g.cx + s / 2f + g.frame, g.ys + s / 2f + g.frame)
        p.color = Color.rgb(0x2E, 0x31, 0x2E)
        cv.drawRoundRect(bez, g.frame * 1.6f, g.frame * 1.6f, p)

        val left = (g.cx - s / 2f).toInt()
        val top = (g.ys - s / 2f).toInt()
        val dst = Rect(left, top, left + s.toInt(), top + s.toInt())
        cv.drawBitmap(screenBmp, src, dst, flat)

        // Drei runde Knoepfe, B in der Mitte tiefer wie bei echten Tamas
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = g.br * 1.05f
        val labels = arrayOf("A", "B", "C")
        val xs = floatArrayOf(g.cx - g.dx, g.cx, g.cx + g.dx)
        val ys = floatArrayOf(g.yb, g.yb + g.br * 0.85f, g.yb)
        for (i in 0..2) {
            val bx = xs[i]; val by = ys[i]
            p.color = Color.rgb(0xEC, 0xEE, 0xF0)
            cv.drawCircle(bx, by, g.br, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = maxOf(2f, mn * 0.008f)
            p.color = Color.rgb(0x8A, 0x8E, 0x92)
            cv.drawCircle(bx, by, g.br, p)
            p.style = Paint.Style.FILL
            tp.color = Color.rgb(0x33, 0x33, 0x33)
            cv.drawText(labels[i], bx, by + tp.textSize * 0.35f, tp)
        }
        return bmp
    }
}
