package com.example.antiwispr.cloud

import android.content.Context
import com.example.antiwispr.AppLog
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.json.JSONObject

/**
 * Initializes Firebase MANUALLY from assets/google-services.json — no google-services
 * Gradle plugin (it fights AGP 9's built-in Kotlin). Drop the file from the Firebase
 * console into app/src/main/assets/ and everything cloud lights up; without it, cloud
 * features stay dark and the app is exactly the local-only build.
 */
object FirebaseBootstrap {

    @Volatile var available: Boolean = false
        private set

    /** Web client id (oauth_client type 3) — the serverClientId for Google sign-in. */
    @Volatile var webClientId: String? = null
        private set

    @Volatile private var initialized = false

    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized) return
        initialized = true
        val ctx = context.applicationContext
        val json = try {
            ctx.assets.open("google-services.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            AppLog.i("[cloud] no assets/google-services.json — cloud features disabled.")
            return
        }
        try {
            val root = JSONObject(json)
            val projectInfo = root.getJSONObject("project_info")
            val client = root.getJSONArray("client").getJSONObject(0)
            val appId = client.getJSONObject("client_info").getString("mobilesdk_app_id")
            val apiKey = client.getJSONArray("api_key").getJSONObject(0).getString("current_key")

            webClientId = findWebClientId(client)

            if (FirebaseApp.getApps(ctx).isEmpty()) {
                FirebaseApp.initializeApp(
                    ctx,
                    FirebaseOptions.Builder()
                        .setApplicationId(appId)
                        .setApiKey(apiKey)
                        .setProjectId(projectInfo.getString("project_id"))
                        .build()
                )
            }
            available = true
            AppLog.i("[cloud] Firebase initialized (project ${projectInfo.getString("project_id")}); webClientId=${if (webClientId != null) "found" else "MISSING — enable the Google provider"}")
        } catch (e: Exception) {
            AppLog.e("[cloud] google-services.json parse/init failed: ${e.message}", e)
        }
    }

    /** The web client id lives in oauth_client (client_type 3) or the appinvite block. */
    private fun findWebClientId(client: JSONObject): String? {
        client.optJSONArray("oauth_client")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                if (c.optInt("client_type") == 3) return c.optString("client_id").ifEmpty { null }
            }
        }
        client.optJSONObject("services")
            ?.optJSONObject("appinvite_service")
            ?.optJSONArray("other_platform_oauth_client")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    if (c.optInt("client_type") == 3) return c.optString("client_id").ifEmpty { null }
                }
            }
        return null
    }
}
