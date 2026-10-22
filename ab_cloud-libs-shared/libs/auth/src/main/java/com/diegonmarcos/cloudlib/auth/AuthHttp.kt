package com.diegonmarcos.cloudlib.auth

/**
 * #1093 The one seam through which this library's fleet calls (the config artifact and vault bundle under
 * api.<domain>/pub, the sign-in probes) can ride an app's own transport. A host that is a declared fleet service goes
 * mesh first and then public with the fleet bearer (libs:fleet-endpoints, installed by Cloud Account); this library
 * stays free of that dependency, so every other app that links it is unchanged: with no [transport], or when it answers
 * null for a URL, the call is the plain HttpURLConnection it always was.
 */
object AuthHttp {
    /** What the server said. [location] is the `Location` header, "" when absent. A redirect is never followed. */
    data class Raw(val code: Int, val body: String, val location: String)

    fun interface Transport {
        /** Null when [url] is not this transport's; an [java.io.IOException] when the request could not be made. */
        fun exchange(method: String, url: String, headers: Map<String, String>, body: String?, connectMs: Int, readMs: Int): Raw?
    }

    @Volatile var transport: Transport? = null
}
