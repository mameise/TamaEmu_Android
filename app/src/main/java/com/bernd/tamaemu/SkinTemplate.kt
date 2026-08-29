package com.bernd.tamaemu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF

/**
 * Vorlage fuer ein eigenes Widget-Bild.
 *
 * Sie zeigt genau dort etwas an, wo die App spaeter zeichnet beziehungsweise
 * tippt: das Feld fuer den Bildschirm und die drei Knopfstellen. Wer darum
 * herum sein Gehaeuse malt, bekommt garantiert ein passendes Ergebnis - die
 * Masse kommen aus derselben Rechnung wie die Anzeige (EggRenderer).
 *
 * Quadratisch, weil das Bild spaeter auf die Widget-Groesse gezogen wird: ein
 * anderes Seitenverhaeltnis wuerde verzerren.
 */
object SkinTemplate {

    /** @param big Vorlage fuer die grosse Ausfuehrung (sonst die kompakte). */
    fun build(ctx: Context, big: Boolean, size: Int = if (big) 768 else 512): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(Color.WHITE)

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val strich = maxOf(2f, size * 0.006f)

        // Aussenkante als Anhalt, wie weit das Bild reicht
        p.style = Paint.Style.STROKE
        p.strokeWidth = strich
        p.color = Color.rgb(0xBB, 0xBB, 0xBB)
        cv.drawRect(RectF(strich, strich, size - strich, size - strich), p)

        // Bildschirmfeld
        val r = EggRenderer.skinScreenRect(size, size, big)
        p.color = Color.rgb(0x33, 0x77, 0xDD)
        p.pathEffect = DashPathEffect(floatArrayOf(size * 0.02f, size * 0.015f), 0f)
        cv.drawRect(RectF(r), p)
        p.pathEffect = null

        p.style = Paint.Style.FILL
        p.color = Color.rgb(0xE8, 0xEF, 0xFB)
        cv.drawRect(RectF(r.left + strich, r.top + strich, r.right - strich, r.bottom - strich), p)

        val t = Paint(Paint.ANTI_ALIAS_FLAG)
        t.textAlign = Paint.Align.CENTER
        t.color = Color.rgb(0x33, 0x77, 0xDD)
        t.textSize = r.width() * 0.10f
        cv.drawText("SCREEN", r.centerX().toFloat(), r.centerY().toFloat(), t)
        t.textSize = r.width() * 0.06f
        cv.drawText("128 x 128", r.centerX().toFloat(), r.centerY() + r.width() * 0.10f, t)

        // Knopfstellen
        val (xs, ys, rad) = EggRenderer.skinButtons(size, size, big)
        val labels = arrayOf("A", "B", "C")
        for (i in 0..2) {
            p.style = Paint.Style.FILL
            p.color = Color.rgb(0xF0, 0xE8, 0xD8)
            cv.drawCircle(xs[i], ys[i], rad, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = strich
            p.color = Color.rgb(0xCC, 0x88, 0x22)
            cv.drawCircle(xs[i], ys[i], rad, p)
            t.color = Color.rgb(0x88, 0x55, 0x11)
            t.textSize = rad * 0.9f
            cv.drawText(labels[i], xs[i], ys[i] + rad * 0.32f, t)
        }

        // Hinweis unten: Beschriftung bewusst knapp und ohne Uebersetzung,
        // damit die Vorlage in jeder Sprache dieselbe Datei sein kann.
        t.color = Color.rgb(0x99, 0x99, 0x99)
        t.textSize = size * 0.028f
        cv.drawText(
            if (big) "TamaEmu skin template - large" else "TamaEmu skin template - compact",
            size / 2f, size * 0.975f, t
        )
        return bmp
    }
}
