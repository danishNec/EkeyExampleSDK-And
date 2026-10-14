package com.example.ekeysdk

import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The result of a completed login: the decoded ID token claims, and the KYC data payload if
 * any `ekyc-bhr-*` scopes were requested and granted.
 */
data class EkeyIdentityData(
    val claims: Map<String, Any?>,
    val kycData: Map<String, Any?>?
)

sealed class EkeyTokenExchangeError {
    data class Network(val error: Throwable) : EkeyTokenExchangeError()
    object BadResponse : EkeyTokenExchangeError()
    object MalformedIdToken : EkeyTokenExchangeError()
}

internal sealed class EkeyTokenExchangeResult {
    data class Success(val identity: EkeyIdentityData) : EkeyTokenExchangeResult()
    data class Failure(val error: EkeyTokenExchangeError) : EkeyTokenExchangeResult()
}

/**
 * Performs the token exchange (integration guide §2.2.5) and KYC data fetch (§6.2) directly
 * inside the SDK. This requires [EkeyLoginConfig.CLIENT_SECRET], which — unlike everywhere else
 * in this SDK — means a secret credential ships inside the compiled app binary. See the
 * comment on `CLIENT_SECRET` for why that's a deliberate trade-off here, not an oversight.
 * Mirrors EkeyTokenExchange.swift on the iOS SDK.
 */
internal object EkeyTokenExchange {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun exchangeAndFetchKyc(
        code: String,
        codeVerifier: String,
        completion: (EkeyTokenExchangeResult) -> Unit
    ) {
        Thread {
            val result = performExchange(code, codeVerifier)
            mainHandler.post { completion(result) }
        }.start()
    }

    private fun performExchange(code: String, codeVerifier: String): EkeyTokenExchangeResult {
        val params = mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "client_id" to EkeyLoginConfig.CLIENT_ID,
            "client_secret" to EkeyLoginConfig.CLIENT_SECRET,
            "code_verifier" to codeVerifier,
            "redirect_uri" to EkeyLoginConfig.REDIRECT_URI
        )

        return try {
            val connection = (URL(EkeyLoginConfig.TOKEN_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            connection.outputStream.use { it.write(formUrlEncode(params).toByteArray(Charsets.UTF_8)) }

            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                return EkeyTokenExchangeResult.Failure(EkeyTokenExchangeError.BadResponse)
            }

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val tokenResponse = JSONObject(responseText)
            val idToken = tokenResponse.optString("id_token").takeIf { it.isNotEmpty() }
                ?: return EkeyTokenExchangeResult.Failure(EkeyTokenExchangeError.BadResponse)

            val claims = decodeJwtPayload(idToken)
                ?: return EkeyTokenExchangeResult.Failure(EkeyTokenExchangeError.MalformedIdToken)

            val kycDataUrlString = claims["urn:oneid:ekyc:bhr:data"] as? String
            val accessToken = tokenResponse.optString("access_token").takeIf { it.isNotEmpty() }

            // TEMP DEBUG: remove before release. Prints values for manual curl testing.
            android.util.Log.d("EkeyDebug", "ACCESS_TOKEN=$accessToken")
            android.util.Log.d("EkeyDebug", "KYC_URL=$kycDataUrlString")

            val kycData = if (kycDataUrlString != null && accessToken != null) {
                fetchKycData(kycDataUrlString, accessToken)
            } else {
                // No KYC scopes granted (or no access_token) — still a successful login, just
                // without a KYC payload to fetch.
                null
            }

            EkeyTokenExchangeResult.Success(EkeyIdentityData(claims = claims, kycData = kycData))
        } catch (e: Exception) {
            EkeyTokenExchangeResult.Failure(EkeyTokenExchangeError.Network(e))
        }
    }

    private fun fetchKycData(urlString: String, accessToken: String): Map<String, Any?>? {
        return try {
            val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $accessToken")
            }
            if (connection.responseCode !in 200..299) return null
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            jsonObjectToMap(JSONObject(responseText))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decodes a JWT's payload segment WITHOUT verifying its signature. Fine for reading the
     * KYC data URL claim; per integration guide §2.2.6, a production system should still
     * verify the signature (against eKey's JWKS), `iss`, `aud`, `exp`, and `iat` before
     * trusting any claim as authenticated user identity. Not done here — this SDK has no JWT
     * library dependency, and adding one is a separate decision from the token-exchange
     * trade-off this file already makes.
     */
    private fun decodeJwtPayload(jwt: String): Map<String, Any?>? {
        val segments = jwt.split(".")
        if (segments.size < 2) return null

        return try {
            var base64 = segments[1].replace('-', '+').replace('_', '/')
            while (base64.length % 4 != 0) base64 += "="
            val decoded = Base64.decode(base64, Base64.DEFAULT)
            jsonObjectToMap(JSONObject(String(decoded, Charsets.UTF_8)))
        } catch (e: Exception) {
            null
        }
    }

    /** Recursively converts org.json's JSONObject/JSONArray into plain Map/List so the result
     * can be handed straight to the RN bridge (which only understands plain Kotlin/Java types),
     * matching what iOS's JSONSerialization.jsonObject already returns for free. */
    private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        json.keys().forEach { key -> map[key] = normalizeJsonValue(json.get(key)) }
        return map
    }

    private fun jsonArrayToList(json: JSONArray): List<Any?> {
        return (0 until json.length()).map { normalizeJsonValue(json.get(it)) }
    }

    private fun normalizeJsonValue(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> jsonArrayToList(value)
        else -> value
    }

    private fun formUrlEncode(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
    }
}
