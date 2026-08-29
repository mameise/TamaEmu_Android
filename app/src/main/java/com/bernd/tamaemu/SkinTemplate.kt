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
 * Sie wird aus DERSELBEN Rechnung gezeichnet wie das Ei (EggRenderer.geo), und
 * zwar bewusst: Bildschirmfeld und Knopfstellen liegen dadurch exakt dort, wo
 * die App sie spaeter zeichnet beziehungsweise antippt. Wer sein Gehaeuse um
 * diese Markierungen herum malt, bekommt ein passgenaues Ergebnis - und der
 * Bildschirm behaelt genau die Groesse, die er auch beim Ei hat.
 *
 * Quadratisch, weil das Bild spaeter auf die Widget-Groesse gezogen wird.
 */
object SkinTemplate {

    /** @param big Vorlage fuer die grosse Ausfuehrung (sonst die kompakte). */
    fun build(ctx: Context, big: Boolean, size: Int = if (big) 768 else 512): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(Color.WHITE)

        val g = EggRenderer.geo(size, size, big)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val strich = maxOf(2f, size * 0.005f)

        // Umriss des Eis als Anhalt, wie weit die App zeichnet
        p.style = Paint.Style.STROKE
        p.strokeWidth = strich
        p.color = Color.rgb(0xCC, 0xCC, 0xCC)
        cv.drawOval(RectF(g.cx - g.a, g.cy - g.b, g.cx + g.a, g.cy + g.b), p)

        // Bildschirmfeld, einschliesslich Rahmen - genau wie in renderEgg
        val s = g.side
        val left = g.cx - s / 2f
        val top = g.ys - s / 2f
        p.color = Color.rgb(0x33, 0x77, 0xDD)
        p.pathEffect = DashPathEffect(floatArrayOf(size * 0.018f, size * 0.012f), 0f)
        cv.drawRect(RectF(left - g.frame, top - g.frame, left + s + g.frame, top + s + g.frame), p)
        p.pathEffect = null
        p.style = Paint.Style.FILL
        p.color = Color.rgb(0xE8, 0xEF, 0xFB)
        cv.drawRect(RectF(left, top, left + s, top + s), p)

        val t = Paint(Paint.ANTI_ALIAS_FLAG)
        t.textAlign = Paint.Align.CENTER
        t.color = Color.rgb(0x33, 0x77, 0xDD)
        t.textSize = s * 0.11f
        cv.drawText("SCREEN", g.cx, g.ys, t)
        t.textSize = s * 0.065f
        cv.drawText("128 x 128", g.cx, g.ys + s * 0.11f, t)

        // Knopfstellen: Mitten, Radius und der tiefere B-Knopf wie beim Ei
        val xs = floatArrayOf(g.cx - g.dx, g.cx, g.cx + g.dx)
        val ys = floatArrayOf(g.yb, g.yb + g.br * 0.85f, g.yb)
        val labels = arrayOf("A", "B", "C")
        for (i in 0..2) {
            p.style = Paint.Style.FILL
            p.color = Color.rgb(0xF0, 0xE8, 0xD8)
            cv.drawCircle(xs[i], ys[i], g.br, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = strich
            p.color = Color.rgb(0xCC, 0x88, 0x22)
            cv.drawCircle(xs[i], ys[i], g.br, p)
            t.color = Color.rgb(0x88, 0x55, 0x11)
            t.textSize = g.br * 0.95f
            cv.drawText(labels[i], xs[i], ys[i] + g.br * 0.34f, t)
        }

        // Knapp und unuebersetzt, damit die Vorlage in jeder Sprache dieselbe ist
        t.color = Color.rgb(0x99, 0x99, 0x99)
        t.textSize = size * 0.026f
        cv.drawText(
            if (big) "TamaEmu skin template - large" else "TamaEmu skin template - compact",
            size / 2f, size * 0.978f, t
        )
        return bmp
    }
}
