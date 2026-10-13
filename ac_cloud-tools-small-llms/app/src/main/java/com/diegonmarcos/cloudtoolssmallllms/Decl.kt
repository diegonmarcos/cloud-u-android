package com.diegonmarcos.cloudtoolssmallllms

import android.util.Base64
import com.diegonmarcos.cloudtoolssmallllms.core.ToolsConfig
import com.diegonmarcos.superapp.bottomnav.NavDecl

/**
 * The two declarations app/build.gradle bakes from build.json, decoded once: build.json::tools
 * (tasks, prompts, workers, router - validated by :tools-core's parser, so a bad edit fails the
 * first launch's parse and the JVM suite, never a screen) and the nav (libs:bottomnav NavDecl).
 */
object Decl {
    val tools: ToolsConfig by lazy {
        ToolsConfig.parse(String(Base64.decode(BuildConfig.TOOLS_CONFIG_B64, Base64.DEFAULT), Charsets.UTF_8))
    }

    val nav: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }
}
