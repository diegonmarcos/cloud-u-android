package com.diegonmarcos.cloudlib.gh

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * #646 THE GITHUB RUNG'S LOGIN — the OAuth 2.0 device grant against GITHUB'S OWN
 * app, so there is nothing for the owner to register and no provider setting to
 * toggle.
 *
 * WHY THIS IS NOT THE DEAD END #629/#641 HIT. That one drove the grant against a
 * client WE declared, which was a GitHub APP (`Ov23li…` prefix), and GitHub Apps
 * ship Device Flow OFF — every attempt answered `device_flow_disabled`, a switch
 * in the provider's settings that no APK can reach. It was deleted rather than
 * documented and it STAYS deleted: nothing here re-declares a provider in
 * `auth.sign_in.providers`. This class uses the ids compiled into the pinned gh
 * binary ([GhRunner.CLIENT_IDS]), and BOTH were exercised live against
 * https://github.com/login/device/code, returning real user codes. The switch is
 * already on, on GitHub's side, for GitHub's own app.
 *
 * WHY IT IS DONE HERE AND NOT BY SHELLING OUT TO `gh auth login`. The eng-spec is
 * explicit that `gh auth login --web` was NOT measured end to end — with no TTY
 * it produced no output and had to be killed at two minutes. What WAS measured is
 * the device-code endpoint and gh's token/API paths. So this drives the flow over
 * HTTPS itself, which is the measured path, and hands the resulting token to gh
 * (and to JGit, and to the terminal) rather than asking gh to own an interactive
 * session an app has no terminal for.
 *
 * NO TOKEN IS EVER RENDERED. [Phase.Prompt] carries the USER code — the short
 * string the owner types at GitHub — and never the access token. [Phase.Granted]
 * carries the token so the caller can put it in the credential store, and its own
 * [toString] is overridden so that an accidental log or string template prints a
 * placeholder instead of the secret.
 */
object GhDeviceLogin {

    /** The scope the git rung needs: clone/fetch/push over HTTPS, and the REST repo listing. */
    const val SCOPE = "repo read:org"

    /** Where the user completes the grant. From the pin, not typed here. */
    val verificationUri: String get() = BuildConfig.GH_VERIFICATION_URI

    /** One observable step of the grant. The UI renders these and nothing else. */
    sealed class Phase {
        /** Asking GitHub to start a grant. */
        object Starting : Phase()

        /**
         * GitHub answered: the owner types [userCode] at [verificationUri]. This
         * is the whole UX tax of this rung — one short code, once per device.
         */
        class Prompt(val userCode: String, val verificationUri: String, val expiresInSeconds: Int) : Phase()

        /** Waiting for the owner to finish in the browser. */
        object Pending : Phase()

        /** Done. [token] goes straight into the credential store; it is never shown. */
        class Granted(val token: String) : Phase() {
            /** So a stray log line or string template cannot leak the secret. */
            override fun toString(): String = "Granted(token=<redacted>)"
        }

        /** The grant failed, worded. [message] never contains a token. */
        class Failed(val message: String) : Phase()
    }

    /**
     * Run the grant to completion, reporting every step to [onPhase].
     *
     * Tries each of the pinned client ids in turn: if GitHub ever retires the
     * first, the second is attempted rather than the rung simply going dark.
     * [now] and [sleep] are injected so the polling loop is testable on the JVM
     * without real time passing.
     */
    fun login(
        clientIds: List<String> = GhRunner.CLIENT_IDS,
        scope: String = SCOPE,
        http: Http = Http.Real,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        onPhase: (Phase) -> Unit = {},
    ): Phase {
        require(clientIds.isNotEmpty()) { "no GitHub client id is pinned: data/gh-binary.json::client_ids is empty" }
        var last: Phase.Failed = Phase.Failed("the grant was never attempted")
        for (clientId in clientIds) {
            onPhase(Phase.Starting)
            val started = try {
                http.post(BuildConfig.GH_DEVICE_CODE_URL, "client_id=$clientId&scope=${enc(scope)}")
            } catch (e: IOException) {
                last = Phase.Failed("GitHub could not be reached to start the grant: ${e.message ?: "no route"}")
                onPhase(last); continue
            }
            val start = JSONObject(started)
            if (start.has("error")) {
                last = Phase.Failed("GitHub refused to start the grant: ${start.optString("error")}")
                onPhase(last); continue
            }
            val deviceCode = start.getString("device_code")
            val userCode = start.getString("user_code")
            val uri = start.optString("verification_uri").ifEmpty { verificationUri }
            val interval = start.optInt("interval", 5).coerceAtLeast(1)
            val expires = start.optInt("expires_in", 900)
            onPhase(Phase.Prompt(userCode, uri, expires))

            val deadline = expires.toLong() * 1000L
            var waited = 0L
            var delay = interval.toLong() * 1000L
            while (waited < deadline) {
                sleep(delay); waited += delay
                onPhase(Phase.Pending)
                val polled = try {
                    http.post(
                        BuildConfig.GH_TOKEN_URL,
                        "client_id=$clientId&device_code=$deviceCode" +
                            "&grant_type=${enc("urn:ietf:params:oauth:device_code")}",
                    )
                } catch (e: IOException) {
                    last = Phase.Failed("GitHub could not be reached while waiting for the grant: ${e.message ?: "no route"}")
                    onPhase(last); break
                }
                val body = JSONObject(polled)
                val token = body.optString("access_token")
                if (token.isNotBlank()) {
                    val granted = Phase.Granted(token)
                    onPhase(granted)
                    return granted
                }
                when (val error = body.optString("error")) {
                    // The owner has not finished in the browser yet. Keep waiting.
                    "authorization_pending" -> Unit
                    // GitHub is asking us to back off; obeying it is what keeps
                    // the grant alive instead of being rate-limited out of it.
                    "slow_down" -> delay += 5000L
                    else -> {
                        last = Phase.Failed(
                            "GitHub ended the grant: ${error.ifBlank { "unrecognised response" }}"
                        )
                        onPhase(last); break
                    }
                }
            }
            if (waited >= deadline) {
                last = Phase.Failed("the code expired before it was entered — start the grant again")
                onPhase(last)
            }
        }
        return last
    }

    /** The one HTTP call this flow makes, injectable so the JVM tests need no network. */
    interface Http {
        /** POST [body] as a form, asking for JSON. Returns the response body. */
        fun post(url: String, body: String): String

        object Real : Http {
            override fun post(url: String, body: String): String {
                val connection = URL(url).openConnection() as HttpURLConnection
                return try {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.connectTimeout = 8000
                    connection.readTimeout = 20000
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connection.outputStream.use { it.write(body.toByteArray()) }
                    val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
                    stream?.bufferedReader()?.readText() ?: "{}"
                } finally {
                    connection.disconnect()
                }
            }
        }
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
