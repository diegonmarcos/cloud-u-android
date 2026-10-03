package com.diegonmarcos.cloudsearch.data

import android.content.Context
import com.diegonmarcos.cloudsearch.Decl
import com.diegonmarcos.cloudsearch.core.Cache
import com.diegonmarcos.cloudsearch.core.Http
import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.cloudsearch.core.SearchEngine
import com.diegonmarcos.cloudsearch.core.UrlHttp
import java.io.File

/**
 * The app's one set of collaborators, shared by the screens and the debug API so a check on the
 * phone runs the same engine, cache and stores the user sees. Results are cached under filesDir
 * (not cacheDir, which the system may empty) so the last answers stay readable offline.
 */
class Services(ctx: Context, val http: Http) {
    /** The application context: the chat's token read and opening a result need one (#823 SearchHost). */
    val app: Context = ctx.applicationContext
    val cfg: SearchConfig = Decl.config
    val engine = SearchEngine(cfg, http, Cache(File(ctx.filesDir, "results")), System::currentTimeMillis)
    val saved = SavedStore(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    val prefs = Prefs(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE), cfg)
    val sessions = SessionStore(File(ctx.filesDir, "chat-sessions.json"))
    val models = ModelCatalog(cfg, http, Cache(File(ctx.filesDir, "catalog")), System::currentTimeMillis)

    companion object {
        const val PREFS = "cloud_search"

        @Volatile private var instance: Services? = null

        fun get(ctx: Context): Services = instance ?: synchronized(this) {
            instance ?: Services(ctx.applicationContext, UrlHttp(Decl.config.userAgent)).also { instance = it }
        }

        /** Tests hand in a fake network; the next [get] builds on it. */
        fun install(s: Services?) { instance = s }
    }
}
