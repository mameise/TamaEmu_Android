package com.bernd.tamaemu

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Sprache der Oberflaeche.
 *
 * Normalerweise folgt eine App der Systemsprache, und das bleibt die Vorgabe.
 * Wer es anders will - etwa ein englisches Handy, aber deutsche Texte - stellt
 * es hier um. Umgesetzt wird das, indem jede Activity und der Dienst ihren
 * Zusammenhang mit der gewaehlten Sprache umhuellen; das funktioniert ab
 * Android 8 und braucht keine zusaetzliche Bibliothek.
 */
object LocaleHelper {

    /** "" = Systemsprache, sonst "en" oder "de". */
    fun wrap(ctx: Context): Context {
        val tag = ctx.uiLanguage
        if (tag.isEmpty()) return ctx
        val loc = Locale.forLanguageTag(tag)
        Locale.setDefault(loc)
        val cfg = Configuration(ctx.resources.configuration)
        cfg.setLocale(loc)
        cfg.setLayoutDirection(loc)
        return ctx.createConfigurationContext(cfg)
    }

    /** Beschriftung fuer den Knopf in den Einstellungen. */
    fun label(ctx: Context): String = when (ctx.uiLanguage) {
        "en" -> "English"
        "de" -> "Deutsch"
        else -> ctx.getString(R.string.lang_system)
    }

    /** Reihum: Systemsprache, Englisch, Deutsch. */
    fun next(ctx: Context) {
        ctx.uiLanguage = when (ctx.uiLanguage) {
            "" -> "en"
            "en" -> "de"
            else -> ""
        }
    }
}
