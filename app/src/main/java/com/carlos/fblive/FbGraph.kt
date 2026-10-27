package com.carlos.fblive

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Una Página de Facebook que administras. */
data class FbPage(val id: String, val name: String, val token: String)

/** Datos de la transmisión creada: la URL (con la clave incluida) a la que se envía el video. */
data class FbLiveInfo(val id: String, val streamUrl: String, val permalink: String?)

/** Lo que Facebook dice de la transmisión en este momento. */
data class FbLiveStatus(val status: String, val viewers: Int?) {
    val isLive: Boolean get() = status == "LIVE" || status == "LIVE_NOW"
    val isEnded: Boolean get() = status == "VOD" || status == "PROCESSING" || status == "LIVE_STOPPED"
}

class FbException(val code: Int, val subcode: Int, message: String) : Exception(message) {
    /** La sesión venció o se invalidó: hay que volver a iniciar sesión. */
    val sessionExpired: Boolean get() = code == 190

    /** Mensaje en español, listo para mostrar. */
    fun friendly(): String {
        val m = message.orEmpty()
        return when {
            sessionExpired -> "La sesión de Facebook venció. Inicia sesión otra vez."
            code == 10 || code == 200 || code == 283 ->
                "Facebook no permitió transmitir: falta un permiso. Cierra sesión, vuelve a entrar y acepta todos los permisos. ($m)"
            m.contains("follower", ignoreCase = true) ->
                "Facebook exige al menos 100 seguidores para transmitir desde un perfil o Página. ($m)"
            m.contains("60 days", ignoreCase = true) ->
                "Facebook exige que la cuenta tenga al menos 60 días. ($m)"
            code == 4 || code == 17 || code == 32 || code == 613 ->
                "Facebook limitó las solicitudes por ahora. Espera unos minutos e intenta de nuevo."
            else -> "Facebook respondió con un error: $m"
        }
    }
}

/**
 * Cliente mínimo de la Graph API de Facebook (solo lo necesario para transmitir).
 * Todas las funciones bloquean: se llaman desde un hilo de fondo, nunca desde la pantalla.
 */
object FbGraph {

    private const val BASE = "https://graph.facebook.com/v26.0/"

    fun me(token: String): String =
        request("GET", "me", token, mapOf("fields" to "id,name")).optString("name", "")

    /** Páginas que administras, cada una con su propio token (necesario para transmitir como la Página). */
    fun pages(userToken: String): List<FbPage> {
        val data = request("GET", "me/accounts", userToken, mapOf("fields" to "id,name,access_token", "limit" to "50"))
            .optJSONArray("data") ?: return emptyList()
        val list = mutableListOf<FbPage>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val token = o.optString("access_token")
            if (id.isNotEmpty() && token.isNotEmpty()) list += FbPage(id, o.optString("name", id), token)
        }
        return list
    }

    /**
     * Crea la transmisión y devuelve la URL de envío. [targetId] es "me" para tu perfil o el id de una Página.
     * [privacy] solo aplica al perfil: EVERYONE, ALL_FRIENDS o SELF.
     */
    fun createLive(targetId: String, token: String, title: String, description: String, privacy: String): FbLiveInfo {
        val params = linkedMapOf("status" to "LIVE_NOW")
        if (title.isNotBlank()) params["title"] = title.trim()
        if (description.isNotBlank()) params["description"] = description.trim()
        if (targetId == "me") params["privacy"] = JSONObject().put("value", privacy).toString()
        val json = request("POST", "$targetId/live_videos", token, params)
        val id = json.optString("id")
        val url = json.optString("secure_stream_url").ifEmpty { json.optString("stream_url") }
        if (id.isEmpty() || url.isEmpty()) throw FbException(0, 0, "Facebook no devolvió la dirección de transmisión")
        val link = runCatching {
            request("GET", id, token, mapOf("fields" to "permalink_url")).optString("permalink_url")
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        val permalink = link?.let { if (it.startsWith("/")) "https://www.facebook.com$it" else it }
        return FbLiveInfo(id, url, permalink)
    }

    fun status(liveId: String, token: String): FbLiveStatus {
        val json = request("GET", liveId, token, mapOf("fields" to "status,live_views"))
        val views = if (json.has("live_views")) json.optInt("live_views") else null
        return FbLiveStatus(json.optString("status", ""), views)
    }

    fun endLive(liveId: String, token: String) {
        request("POST", liveId, token, mapOf("end_live_video" to "true"))
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun request(method: String, path: String, token: String, params: Map<String, String>): JSONObject {
        val all = LinkedHashMap(params).apply { put("access_token", token) }
        val query = all.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val post = method == "POST"
        val conn = URL(BASE + path + if (post) "" else "?$query").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            if (post) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(query.toByteArray(Charsets.UTF_8)) }
            }
            val http = conn.responseCode
            val stream = if (http in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            val error = json?.optJSONObject("error")
            if (error != null || http !in 200..299) {
                val msg = error?.optString("error_user_msg")?.takeIf { it.isNotEmpty() }
                    ?: error?.optString("message")?.takeIf { it.isNotEmpty() }
                    ?: "HTTP $http"
                throw FbException(error?.optInt("code") ?: http, error?.optInt("error_subcode") ?: 0, msg)
            }
            return json ?: JSONObject()
        } finally {
            conn.disconnect()
        }
    }
}
