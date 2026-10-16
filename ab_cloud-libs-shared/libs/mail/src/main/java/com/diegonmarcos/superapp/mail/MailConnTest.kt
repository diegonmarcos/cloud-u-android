package com.diegonmarcos.superapp.mail

import android.content.Context

/** Test connection: one session discovery with the credentials as typed, reported in words. */
object MailConnTest {
    data class Outcome(val ok: Boolean, val message: String, val problem: ConnProblem? = null, val httpCode: Int? = null)

    /** Blocking; call off the main thread. [creds] are the (possibly unsaved) gateway credentials. */
    fun run(ctx: Context, server: String, email: String, password: String, creds: MailAuthCreds): Outcome {
        val http = MailHttp.client(server, { creds })
        return when (val r = JmapClient(server.trim(), email.trim(), password, http).discover()) {
            is JmapClient.Result.Success -> Outcome(true, ctx.getString(R.string.mail_auth_ok, r.value.primaryAccountId))
            is JmapClient.Result.HttpError -> {
                val p = ConnErrors.classify(r.code, null)
                Outcome(false, describe(ctx, p, r.code, ""), p, r.code)
            }
            is JmapClient.Result.NetworkError -> {
                val p = ConnErrors.classify(null, r.cause)
                Outcome(false, describe(ctx, p, null, r.message), p)
            }
        }
    }

    fun describe(ctx: Context, p: ConnProblem, code: Int?, detail: String): String = when (p) {
        ConnProblem.UNAUTHORIZED -> ctx.getString(R.string.mail_auth_err_401)
        ConnProblem.FORBIDDEN -> ctx.getString(R.string.mail_auth_err_403)
        ConnProblem.TLS -> ctx.getString(R.string.mail_auth_err_tls)
        ConnProblem.DNS -> ctx.getString(R.string.mail_auth_err_dns)
        ConnProblem.TIMEOUT -> ctx.getString(R.string.mail_auth_err_timeout)
        ConnProblem.REFUSED -> ctx.getString(R.string.mail_auth_err_refused)
        ConnProblem.HTTP -> ctx.getString(R.string.mail_auth_err_http, code ?: 0)
        ConnProblem.OTHER -> ctx.getString(R.string.mail_auth_err_other, detail.take(120))
    }
}
