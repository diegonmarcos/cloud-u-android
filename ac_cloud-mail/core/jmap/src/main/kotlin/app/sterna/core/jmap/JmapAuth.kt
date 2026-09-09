package app.sterna.core.jmap

import java.util.Base64

/** How a JMAP request authenticates. Basic auth for now; Bearer/OAuth-ready. */
sealed interface JmapAuth {
    fun authorizationHeader(): String
}

data class BasicAuth(val username: String, val password: String) : JmapAuth {
    override fun authorizationHeader(): String {
        val token = Base64.getEncoder()
            .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        return "Basic $token"
    }
}

data class BearerAuth(val token: String) : JmapAuth {
    override fun authorizationHeader(): String = "Bearer $token"
}
