package com.diegonmarcos.cloudbrowser.agentapi

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.diegonmarcos.cloudbrowser.MainActivity
import com.diegonmarcos.superapp.browser.BrowserTabPrefs

/**
 * #913 the OPEN half of the fleet agent door (see [AgentApiContract]): shows a URL to the person in Cloud
 * Browser, optionally filed in a named tab group, then gets out of the way. It has no UI of its own and
 * returns no page content; it fills nothing in and submits nothing. Exported behind the signature
 * permission (the AuthMissionActivity pattern), so only a fleet app can start it.
 */
class AgentOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parsed = AgentApiContract.openRequest(
            intent.getStringExtra(AgentApiContract.EXTRA_URL) ?: intent.dataString,
            intent.getStringExtra(AgentApiContract.EXTRA_GROUP),
        )
        if (parsed is AgentApiContract.OpenParsed.Ok) {
            val tabs = BrowserTabPrefs(this)
            tabs.add(parsed.url, parsed.url)
            // add() keeps a tab's group when it re-opens a url, so the host's own open below leaves it filed.
            if (parsed.group.isNotEmpty()) tabs.setGroup(parsed.url, parsed.group)
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(parsed.url))
                    .setClass(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            setResult(RESULT_OK)
        } else {
            setResult(RESULT_CANCELED, Intent().putExtra("error", (parsed as AgentApiContract.OpenParsed.Refused).why))
        }
        finish()
    }
}
