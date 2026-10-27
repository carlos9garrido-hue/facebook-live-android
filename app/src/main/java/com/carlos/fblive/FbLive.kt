package com.carlos.fblive

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.facebook.AccessToken
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Ciclo de vida de la transmisión en Facebook: crearla al tocar LIVE, vigilar lo que Facebook
 * confirma (estado y espectadores) y terminarla al tocar STOP.
 */
class FbLive(private val a: MainActivity) {

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    @Volatile
    private var generation = 0

    @Volatile
    var liveId: String? = null
        private set

    @Volatile
    var permalink: String? = null
        private set

    @Volatile
    private var liveToken: String? = null
    private var polling = false
    private var pollStartedAt = 0L
    private var expiredWarned = false

    /** Páginas del usuario (null = todavía sin cargar). */
    @Volatile
    var pages: List<FbPage>? = null
        private set

    /** Nombre de la cuenta con la sesión iniciada. */
    @Volatile
    var userName: String = ""
        private set

    /** Carga nombre y Páginas después de iniciar sesión. [onDone] corre en la pantalla principal. */
    fun loadAccount(onDone: () -> Unit) {
        val token = AccessToken.getCurrentAccessToken()?.takeIf { !it.isExpired }?.token ?: return
        io.execute {
            runCatching { userName = FbGraph.me(token) }
            pages = runCatching { FbGraph.pages(token) }.getOrDefault(emptyList())
            ui.post(onDone)
        }
    }

    fun clearAccount() {
        pages = null
        userName = ""
    }

    /** Crea la transmisión. [onResult] corre en la pantalla principal, salvo que se haya cancelado. */
    fun create(onResult: (Result<FbLiveInfo>) -> Unit) {
        val userToken = AccessToken.getCurrentAccessToken()?.takeIf { !it.isExpired }?.token
        if (userToken == null) {
            onResult(Result.failure(FbException(190, 0, "Sin sesión")))
            return
        }
        val settings = a.settings
        val targetId = settings.fbTargetId.ifEmpty { "me" }
        val title = settings.fbTitle
        val description = settings.fbDescription
        val privacy = settings.fbPrivacy
        val mine = ++generation
        io.execute {
            val result = runCatching {
                val token = if (targetId == "me") {
                    userToken
                } else {
                    // Para transmitir como Página hace falta el token propio de esa Página.
                    (pages ?: FbGraph.pages(userToken)).firstOrNull { it.id == targetId }?.token
                        ?: throw FbException(0, 0, "No se encontró la Página elegida. Revisa que sigas siendo administrador.")
                }
                val info = FbGraph.createLive(targetId, token, title, description, privacy)
                liveToken = token
                info
            }
            ui.post {
                if (mine != generation) {
                    // Se canceló mientras se creaba: se cierra la transmisión que quedó abierta.
                    result.getOrNull()?.let { closeInBackground(it.id, liveToken) }
                    return@post
                }
                result.getOrNull()?.let {
                    liveId = it.id
                    permalink = it.permalink
                }
                onResult(result)
            }
        }
    }

    /** El usuario tocó STOP mientras se creaba la transmisión. */
    fun cancelCreate() {
        generation++
    }

    /** Empieza a preguntarle a Facebook por el estado, cada pocos segundos. */
    fun startPolling() {
        if (liveId == null || polling) return
        polling = true
        pollStartedAt = SystemClock.elapsedRealtime()
        expiredWarned = false
        ui.postDelayed(pollTick, 3000)
    }

    private val pollTick = object : Runnable {
        override fun run() {
            val id = liveId
            val token = liveToken
            if (!polling || id == null || token == null) return
            val mine = generation
            io.execute {
                val result = runCatching { FbGraph.status(id, token) }
                ui.post {
                    if (!polling || mine != generation) return@post
                    result.onSuccess { s ->
                        val sinceStart = SystemClock.elapsedRealtime() - pollStartedAt
                        a.onFbStatus(s, sinceStart)
                    }.onFailure { e ->
                        if (e is FbException && e.sessionExpired && !expiredWarned) {
                            expiredWarned = true
                            a.toast("La sesión de Facebook venció. La transmisión sigue, pero ya no puedo verificarla.")
                        }
                    }
                }
            }
            ui.postDelayed(this, 4000)
        }
    }

    /** Termina la transmisión en Facebook (si hay una) y deja de consultar. */
    fun end() {
        polling = false
        ui.removeCallbacks(pollTick)
        generation++
        val id = liveId
        val token = liveToken
        liveId = null
        permalink = null
        liveToken = null
        if (id != null) closeInBackground(id, token)
    }

    fun release() {
        polling = false
        ui.removeCallbacks(pollTick)
        io.shutdown()
    }

    private fun closeInBackground(id: String, token: String?) {
        if (token == null) return
        runCatching { io.execute { runCatching { FbGraph.endLive(id, token) } } }
    }
}
