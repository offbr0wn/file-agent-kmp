package dev.fileagent.app

import android.content.SharedPreferences
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What the File Agent AI server says about this person's account. */
data class HostedState(
    val signedUp: Boolean = false,
    val loading: Boolean = false,
    val plan: String = "free",
    val planUntil: Long = 0,
    val monthlyLeft: Int = 0,
    val packLeft: Int = 0,
    val planMessages: Int = 1000,
    val packMessages: Int = 500,
    val planLink: String = "",
    val packLink: String = "",
    val error: String? = null,
) {
    val left: Int get() = monthlyLeft + packLeft
}

/**
 * The paid File Agent AI. Each install gets a private account on our server (no email).
 * The account key is kept locked on the phone and can be copied out as a backup.
 */
class HostedAccount(private val http: HttpClient, private val prefs: SharedPreferences) {

    val token: String get() = Vault.open(prefs.getString(KEY, "") ?: "")

    fun setToken(t: String) = prefs.edit().putString(KEY, Vault.seal(t.trim())).apply()

    /** Makes the private account the first time it is needed. */
    suspend fun ensure(): String = withContext(Dispatchers.IO) {
        token.takeIf { it.isNotBlank() }?.let { return@withContext it }
        val res = http.post("$SERVER/v1/register")
        val text = res.bodyAsText()
        if (!res.status.isSuccess()) error(message(text) ?: "Couldn't set up File Agent AI. Try again.")
        JSONObject(text).getString("token").also { setToken(it) }
    }

    suspend fun refresh(): HostedState = withContext(Dispatchers.IO) {
        val t = token
        if (t.isBlank()) return@withContext HostedState()
        val res = http.get("$SERVER/v1/account") { header("authorization", "Bearer $t") }
        val text = res.bodyAsText()
        if (res.status.value == 401) return@withContext HostedState(signedUp = true, error = "This account key isn't valid.")
        if (!res.status.isSuccess()) error(message(text) ?: "Can't reach File Agent AI")
        val o = JSONObject(text)
        HostedState(
            signedUp = true,
            plan = o.optString("plan", "free"),
            planUntil = o.optLong("plan_until"),
            monthlyLeft = o.optInt("monthly_left"),
            packLeft = o.optInt("pack_left"),
            planMessages = o.optInt("plan_messages", 1000),
            packMessages = o.optInt("pack_messages", 500),
            planLink = o.optString("plan_link"),
            packLink = o.optString("pack_link"),
        )
    }

    /**
     * A tag for this account that Google Play keeps with each purchase. It is the same hash our
     * server stores, so the key itself never leaves the phone.
     */
    fun accountTag(): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    /** The Plus purchase this phone last had confirmed, so it isn't checked again on every start. */
    var lastPlayToken: String
        get() = prefs.getString(PLAY_KEY, "") ?: ""
        set(v) = prefs.edit().putString(PLAY_KEY, v).apply()

    /** Sends a Google Play purchase to our server, which checks it with Google and adds the messages. */
    suspend fun verifyPlay(purchaseToken: String, productId: String, kind: String) = withContext(Dispatchers.IO) {
        val t = ensure()
        val body = JSONObject().put("token", purchaseToken).put("product", productId).put("kind", kind).toString()
        val res = http.post("$SERVER/v1/play/verify") {
            header("authorization", "Bearer $t")
            setBody(TextContent(body, ContentType.Application.Json))
        }
        val text = res.bodyAsText()
        if (!res.status.isSuccess()) error(message(text) ?: "Couldn't confirm the purchase")
    }

    private fun message(text: String): String? =
        runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()

    companion object {
        /** The File Agent AI server (Cloudflare Worker). */
        const val SERVER = "https://file-agent.SUBDOMAIN.workers.dev"
        private const val KEY = "hosted_token"
        private const val PLAY_KEY = "play_plus_token"
    }
}
