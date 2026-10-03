package com.diegonmarcos.superapp.browser

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import android.webkit.WebView
import org.json.JSONObject

/**
 * #802 I10 the Cloud Vault add-on: logins and cards are filled by Cloud Vault's own
 * AutofillService through the Android Autofill Framework, so the browser holds no password
 * and links no vault SDK. This object only reports, asks and opens. [pkg] is the add-on's
 * requires_package (the fleet manifest's vault row, resolved at build time).
 */
object VaultAutofill {

    /** The component Android has as the autofill service, or "" (a hidden-but-readable secure setting). */
    fun serviceComponent(ctx: Context): String =
        runCatching { Settings.Secure.getString(ctx.contentResolver, "autofill_service") }.getOrNull().orEmpty()

    /** True when [component] (pkg/class, either form) belongs to [pkg]. Pure. */
    fun isVault(component: String, pkg: String): Boolean =
        pkg.isNotBlank() && ComponentName.unflattenFromString(component)?.packageName == pkg

    fun status(ctx: Context, pkg: String?): JSONObject {
        val o = JSONObject().put("package", pkg ?: JSONObject.NULL)
        if (pkg.isNullOrBlank()) return o.put("installed", false).put("why", "the vault add-on names no fleet app")
        val installed = runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        val comp = serviceComponent(ctx)
        o.put("installed", installed).put("autofill_service", comp.ifBlank { JSONObject.NULL })
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return o.put("enabled", false).put("why", "Android autofill needs Android 8")
        val am = ctx.getSystemService(AutofillManager::class.java)
        val vault = isVault(comp, pkg)
        o.put("framework_enabled", am?.isEnabled == true).put("enabled", vault && am?.isEnabled == true)
        if (!installed) o.put("why", "Cloud Vault is not installed (Store)")
        else if (!vault) o.put("why", "Cloud Vault is not the autofill service: menu > Use Cloud Vault for autofill")
        return o
    }

    /**
     * Focus the page's first login field, then ask the framework to show the autofill
     * service's fill UI for the WebView. Answers whether the request was made.
     */
    fun requestFill(wv: WebView, done: (JSONObject) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return done(JSONObject().put("requested", false).put("why", "Android autofill needs Android 8"))
        val am = wv.context.getSystemService(AutofillManager::class.java)
        if (am == null || !am.isEnabled) return done(JSONObject().put("requested", false).put("why", "Android autofill is off on this phone"))
        val js = "(function(){var e=document.querySelector('input[autocomplete~=username],input[type=email],input[type=password],input[name*=user i],input[name*=login i]');" +
            "if(!e)return JSON.stringify({field:false});e.focus();return JSON.stringify({field:true,type:e.type});})()"
        BrowserPageActions.run(wv, js) { r ->
            wv.requestFocus()
            am.requestAutofill(wv)
            done(JSONObject().put("requested", true).put("login_field", r?.optBoolean("field") == true))
        }
    }

    /** Cloud Vault's own screen (it has no search-by-host extra to pass). */
    fun open(ctx: Context, pkg: String?): String? {
        val i = pkg?.let { ctx.packageManager.getLaunchIntentForPackage(it) } ?: return "Cloud Vault is not installed"
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }

    /** Android's own "use this app for autofill" prompt, for Cloud Vault. */
    fun setAsService(ctx: Context, pkg: String?): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "Android autofill needs Android 8"
        if (pkg.isNullOrBlank()) return "the vault add-on names no fleet app"
        val i = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(i); null }.getOrElse {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "opened Settings: pick Cloud Vault under Autofill service" }
                .getOrElse { "no settings screen for autofill on this phone" }
        }
    }
}
