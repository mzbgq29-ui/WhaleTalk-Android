package io.astrbot.android

import android.app.AlertDialog
import android.content.Context
import android.webkit.WebView

/** Phone backends use readable 100% layouts and retain pinch zoom for accessibility. */
internal class BrowserScale(private val context: Context, private val key: String) {
    private val preferences = context.getSharedPreferences("browser-scale", Context.MODE_PRIVATE)
    private val phonePage = key != "desktop"
    private val defaultPercent get() = if (phonePage) 100 else 75
    val percent get() = preferences.getInt(key, defaultPercent).takeIf { it in levels } ?: defaultPercent

    fun attach(view: WebView) {
        view.settings.setSupportZoom(phonePage)
        view.settings.builtInZoomControls = phonePage
        view.settings.displayZoomControls = false
        view.settings.useWideViewPort = true
        view.settings.loadWithOverviewMode = false
        view.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) apply(view)
        }
    }

    fun apply(view: WebView) {
        if (view.width <= 0) return
        val scale = percent / 100.0
        val cssWidth = (view.width / context.resources.displayMetrics.density / scale).toInt().coerceAtLeast(1)
        val limits = if (phonePage) "minimum-scale=${minOf(scale, .5)},maximum-scale=${maxOf(scale, 3.0)},user-scalable=yes"
            else "minimum-scale=$scale,maximum-scale=$scale,user-scalable=no"
        view.evaluateJavascript("""
            (() => {
              if (!document.head) return false;
              let tags = [...document.querySelectorAll('meta[name="viewport"]')];
              if (!tags.length) { const m=document.createElement('meta');m.name='viewport';document.head.append(m);tags=[m]; }
              const value='width=$cssWidth,initial-scale=$scale,$limits';
              for (const tag of tags) if (tag.content!==value) tag.content=value;
              return true;
            })();
        """.trimIndent(), null)
    }

    fun choose(view: WebView, onChanged: () -> Unit = {}) {
        AlertDialog.Builder(context).setTitle(AppText.get(R.string.ui_page_zoom))
            .setSingleChoiceItems(levels.map { "$it%" }.toTypedArray(), levels.indexOf(percent)) { dialog, index ->
                preferences.edit().putInt(key, levels[index]).apply()
                apply(view); onChanged(); dialog.dismiss()
            }.setNegativeButton(AppText.get(R.string.ui_cancel), null).show()
    }

    companion object { val levels = listOf(125, 110, 100, 90, 75, 50, 25) }
}
