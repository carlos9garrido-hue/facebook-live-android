package com.carlos.fblive

import com.facebook.AccessToken
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult

/** Inicio de sesión con Facebook. El SDK guarda y renueva la sesión por su cuenta. */
class FbAuth(private val a: MainActivity) {

    private val callbackManager = CallbackManager.Factory.create()

    /** false mientras el ID de la app siga con el valor de relleno de facebook.xml. */
    val configured: Boolean
        get() {
            val id = a.getString(R.string.facebook_app_id)
            val token = a.getString(R.string.facebook_client_token)
            return id.isNotBlank() && !id.startsWith("TU_") && token.isNotBlank() && !token.startsWith("TU_")
        }

    val token: AccessToken?
        get() = AccessToken.getCurrentAccessToken()?.takeIf { !it.isExpired }

    val loggedIn: Boolean get() = token != null

    fun hasPermission(permission: String): Boolean = token?.permissions?.contains(permission) == true

    /** [onDone] recibe si salió bien y un mensaje para mostrar (puede ser null). Se llama en la pantalla principal. */
    fun login(onDone: (Boolean, String?) -> Unit) {
        val manager = LoginManager.getInstance()
        manager.registerCallback(callbackManager, object : FacebookCallback<LoginResult> {
            override fun onSuccess(result: LoginResult) {
                val missing = REQUIRED.filter { it !in result.accessToken.permissions }
                if (missing.isEmpty()) {
                    onDone(true, null)
                } else {
                    onDone(true, "Sesión iniciada, pero faltan permisos: ${missing.joinToString(", ")}")
                }
            }

            override fun onCancel() = onDone(false, "Inicio de sesión cancelado")

            override fun onError(error: FacebookException) =
                onDone(false, "No se pudo iniciar sesión: ${error.message}")
        })
        manager.logIn(a, callbackManager, PERMISSIONS)
    }

    fun logout() {
        LoginManager.getInstance().logOut()
    }

    companion object {
        const val PERM_PUBLISH_VIDEO = "publish_video"
        const val PERM_PAGES_POST = "pages_manage_posts"

        /** Permisos que se piden: perfil (publish_video) y Páginas (las otras tres). */
        val PERMISSIONS = listOf(
            "public_profile",
            PERM_PUBLISH_VIDEO,
            "pages_show_list",
            "pages_read_engagement",
            PERM_PAGES_POST
        )

        private val REQUIRED = listOf(PERM_PUBLISH_VIDEO)
    }
}
