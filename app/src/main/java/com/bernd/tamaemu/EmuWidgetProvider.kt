package com.bernd.tamaemu

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.widget.RemoteViews

/**
 * Ei-Widget: das Panel in einem Tamagotchi-Ei mit drei runden A/B/C-Buttons
 * unten und Zahnrad oben rechts. Gezeichnet wird alles als ein Bitmap
 * (EggRenderer); die Buttons sind unsichtbare Tap-Flaechen im Layout darueber.
 * Getaktet wird aus EmuService (Vorgabe 2 Bilder/s).
 */
open class EmuWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACT_A = "com.bernd.tamaemu.EGG_A"
        const val ACT_B = "com.bernd.tamaemu.EGG_B"
        const val ACT_C = "com.bernd.tamaemu.EGG_C"

        private val px = IntArray(EmuNative.W * EmuNative.H)
        private var lastFrame = 0L

        /** Beide Ausfuehrungen in ihrer echten Groesse zeichnen. */
        fun renderWidgets(ctx: Context, force: Boolean = false) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val small = mgr.getAppWidgetIds(ComponentName(ctx, EmuWidgetProvider::class.java))
            val large = mgr.getAppWidgetIds(ComponentName(ctx, EmuWidgetBigProvider::class.java))
            if (small.isEmpty() && large.isEmpty()) return
            val warm = EmuNative.isLoaded()
            if (warm) {
                val n = EmuNative.frame(px)
                if (n == lastFrame && !force) return
                lastFrame = n
                EggRenderer.pushPixels(px)
            }
            for (id in small) draw(ctx, mgr, id, warm, false)
            for (id in large) draw(ctx, mgr, id, warm, true)
        }

        private fun draw(ctx: Context, mgr: AppWidgetManager, id: Int,
                         warm: Boolean, big: Boolean) {
            val bmp = if (warm) {
                val (w, h) = sizePx(ctx, mgr, id)
                EggRenderer.renderEgg(ctx, w, h, big)
            } else null
            mgr.updateAppWidget(id, buildViews(ctx, warm, bmp, big))
        }

        /** Aktuelle Widget-Groesse in Pixeln (Hochformat), gedeckelt. */
        private fun sizePx(ctx: Context, mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
            val o = mgr.getAppWidgetOptions(id)
            val d = ctx.resources.displayMetrics.density
            var w = (o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) * d).toInt()
            var h = (o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) * d).toInt()
            if (w <= 0) w = (160 * d).toInt()
            if (h <= 0) h = (220 * d).toInt()
            val longest = maxOf(w, h)
            if (longest > 640) { val s = 640f / longest; w = (w * s).toInt(); h = (h * s).toInt() }
            if (w < 80) w = 80
            if (h < 80) h = 80
            return Pair(w, h)
        }

        private fun buildViews(ctx: Context, warm: Boolean, bmp: Bitmap?,
                               big: Boolean = false): RemoteViews {
            val rv = RemoteViews(ctx.packageName,
                if (big) R.layout.widget_egg_big else R.layout.widget_egg)
            if (warm && bmp != null) {
                rv.setViewVisibility(R.id.egg_image, android.view.View.VISIBLE)
                rv.setViewVisibility(R.id.egg_hint, android.view.View.GONE)
                rv.setImageViewBitmap(R.id.egg_image, bmp)
            } else {
                rv.setViewVisibility(R.id.egg_image, android.view.View.GONE)
                rv.setViewVisibility(R.id.egg_hint, android.view.View.VISIBLE)
            }
            rv.setOnClickPendingIntent(R.id.egg_a, broadcast(ctx, ACT_A, 21))
            rv.setOnClickPendingIntent(R.id.egg_b, broadcast(ctx, ACT_B, 22))
            rv.setOnClickPendingIntent(R.id.egg_c, broadcast(ctx, ACT_C, 23))
            rv.setOnClickPendingIntent(R.id.egg_gear, gearIntent(ctx))
            rv.setOnClickPendingIntent(R.id.egg_image, openAppIntent(ctx))
            rv.setOnClickPendingIntent(R.id.egg_hint, openAppIntent(ctx))
            return rv
        }

        private fun flags(): Int {
            var f = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) f = f or PendingIntent.FLAG_IMMUTABLE
            return f
        }

        private fun broadcast(ctx: Context, action: String, code: Int): PendingIntent {
            val i = Intent(ctx, EmuWidgetProvider::class.java).setAction(action)
            return PendingIntent.getBroadcast(ctx, code, i, flags())
        }

        private fun gearIntent(ctx: Context): PendingIntent {
            val i = Intent(ctx, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(ctx, 29, i, flags())
        }

        private fun openAppIntent(ctx: Context): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(ctx, 30, i, flags())
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        EmuService.start(ctx)
        renderWidgets(ctx, force = true)
    }

    override fun onEnabled(ctx: Context) { EmuService.start(ctx) }

    override fun onAppWidgetOptionsChanged(
        ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle
    ) {
        renderWidgets(ctx, force = true)   // in neuer Groesse neu zeichnen
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        val mask = when (intent.action) {
            ACT_A -> EmuNative.BTN_A
            ACT_B -> EmuNative.BTN_B
            ACT_C -> EmuNative.BTN_C
            else -> return
        }
        val pending = goAsync()
        val app = ctx.applicationContext
        Thread {
            try {
                if (app.userQuit) app.userQuit = false   // Druck = bewusst geweckt
                EmuService.start(app)
                if (EmuService.waitWarm(app, 2000)) {
                    Input.tap(mask)          // EIN sauberer, kurzer Druck
                    Thread.sleep(200)        // Firmware Zeit zum Reagieren geben
                    renderWidgets(app, force = true)
                }
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/**
 * Zweite Ausfuehrung mit groesserem Bildschirm. Gleiche Bedienung, nur wird das
 * Ei flacher gezeichnet, damit das Tama mehr Platz bekommt.
 */
class EmuWidgetBigProvider : EmuWidgetProvider()

/** Nach dem Neustart des Handys wieder mitlaufen. */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && ctx.bgRun) EmuService.start(ctx)
    }
}
