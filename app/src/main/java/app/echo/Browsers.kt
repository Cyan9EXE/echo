package app.echo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager

/**
 * Opens links in a real, user-installed browser instead of whatever the system
 * default happens to be (often a bare WebView shell).
 *
 * Order: known good browsers (Chrome, Edge, Firefox, ... ) -> any user-installed
 * browser that is not the system default -> the system default as a last resort.
 * The "quality" list is deliberately not exhaustive: new browsers can be added
 * here, and anything else the user installed still beats the default shell.
 */
internal object Browsers {

    private val preference = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.microsoft.emmx",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.focus",
        "com.brave.browser",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.sec.android.app.sbrowser",
        "com.tencent.mtt",
        "com.UCMobile",
        "com.quark.browser",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "org.cromite.cromite",
        "com.duckduckgo.mobile.android",
        "com.yandex.browser",
    )

    fun open(context: Context, url: String) {
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri)
        val pm = context.packageManager

        @Suppress("DEPRECATION")
        val handlers = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                pm.queryIntentActivities(intent, 0)
            }
        }.getOrNull().orEmpty()

        val installed = handlers.mapNotNull { it.activityInfo?.packageName }.toSet()
        val defaultPkg = runCatching {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 33) {
                pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }?.activityInfo?.packageName
        }.getOrNull()

        val chosen = preference.firstOrNull { it in installed }
            ?: installed.firstOrNull { it != defaultPkg && it != context.packageName && it != "android" }
            ?: defaultPkg

        if (chosen != null) {
            intent.setPackage(chosen)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .onFailure { fallback(context, uri) }
        } else {
            fallback(context, uri)
        }
    }

    private fun fallback(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
