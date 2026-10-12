package com.easyesuite.app.di

import android.content.Context
import com.easyesuite.app.BuildConfig
import com.easyesuite.app.data.SecureTokenStore
import com.easyesuite.core.ApiConfig
import com.easyesuite.core.FirebaseConfig
import com.easyesuite.core.auth.AuthService
import com.easyesuite.core.auth.Session
import com.easyesuite.core.net.ApiClient
import com.easyesuite.core.net.FirestoreClient
import com.easyesuite.core.repo.AssistantRepository
import com.easyesuite.core.repo.InventoryRepository
import com.easyesuite.core.repo.ItemsRepository
import com.easyesuite.core.repo.OrdersRepository
import com.easyesuite.core.repo.ReportsRepository
import com.easyesuite.core.repo.ShippingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Hand-rolled DI: one graph per signed-in workspace. The API client is rebuilt when the workspace
 * changes (login/logout) because its `schema_name` is part of every URL.
 */
class AppContainer(context: Context) {

    val tokenStore = SecureTokenStore(context.applicationContext)

    private val okHttp: OkHttpClient = OkHttpClient.Builder().apply {
        if (BuildConfig.DEBUG) {
            // BASIC logs method + URL + status only — never headers or bodies (tokens must not reach logcat).
            addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        }
    }.build()

    /** Null = signed out. Observed by the root composable to switch between login and the app. */
    private val _session = MutableStateFlow(tokenStore.load()?.takeIf { it.tenant.isNotBlank() })
    val session: StateFlow<Session?> = _session

    /** "Switch workspace" from Settings: the login screen is shown over the app while the current session stays valid. */
    private val _switchingWorkspace = MutableStateFlow(false)
    val switchingWorkspace: StateFlow<Boolean> = _switchingWorkspace

    @Volatile private var graph: Graph? = _session.value?.let { Graph(it.tenant) }

    inner class Graph(val tenant: String) {
        val config = ApiConfig(tenant = tenant, apiRoot = BuildConfig.API_ROOT, firebase = firebaseConfig)
        val client = ApiClient(
            config = config,
            tokenStore = tokenStore,
            baseClient = okHttp,
            onSessionExpired = { signOut() },
        )
        val auth = AuthService(client)
        val items = ItemsRepository(client)
        val inventory = InventoryRepository(client)
        val orders = OrdersRepository(client)
        val shipping = ShippingRepository(client)
        val reports = ReportsRepository(client)
        val assistant = AssistantRepository(client)
        /** Firestore-backed data (Copilot conversations, connector status) — null when the build has no Firebase config. */
        val firestoreConnector: FirestoreClient? = firebaseConfig?.let { FirestoreClient(it, it.connectorDatabase, auth, client.httpClient) }
        /** In-app notifications — null when the build has no Firebase config. */
        val firestoreNotifications: FirestoreClient? = firebaseConfig?.let { FirestoreClient(it, it.notificationsDatabase, auth, client.httpClient) }
    }

    /** Graph for the active workspace; throws if used while signed out (a programming error). */
    fun graph(): Graph = graph ?: error("No active session")

    /**
     * A graph for signing in: blank tenant = the global root (step 1, listing workspaces); a `schema_name` =
     * that workspace (step 2, the actual login).
     */
    fun graphFor(tenant: String = ""): Graph = Graph(tenant)

    fun onSignedIn(session: Session) {
        tokenStore.lastTenant = session.tenant
        tokenStore.lastEmail = session.email
        graph = Graph(session.tenant)
        _switchingWorkspace.value = false
        _session.value = session
    }

    /**
     * Keep the current workspace until the new login succeeds; the login screen asks for the password again
     * (the backend's login is per workspace, and the app never stores the password).
     */
    fun switchWorkspace() { _switchingWorkspace.value = true }
    fun cancelWorkspaceSwitch() { _switchingWorkspace.value = false }

    /** Delete both JWTs and the Firebase tokens; nothing is cached locally beyond that. */
    fun signOut() {
        tokenStore.clear()
        _switchingWorkspace.value = false
        graph = null
        _session.value = null
    }

    companion object {
        /** "Production" / "Staging" / "Development" for the Settings screen. */
        val environmentName: String get() = ApiConfig(tenant = "", apiRoot = BuildConfig.API_ROOT).environmentName

        /** Build-time Firebase configuration (android/easyesuite.properties or CI secrets); null = REST only. */
        val firebaseConfig: FirebaseConfig? = BuildConfig.FIREBASE_API_KEY.takeIf { it.isNotBlank() }?.let { key ->
            BuildConfig.FIREBASE_PROJECT_ID.takeIf { it.isNotBlank() }?.let { project ->
                FirebaseConfig(
                    apiKey = key, projectId = project,
                    connectorDatabase = BuildConfig.FIRESTORE_CONNECTOR_DB.ifBlank { "(default)" },
                    notificationsDatabase = BuildConfig.FIRESTORE_NOTIFICATIONS_DB.ifBlank { "(default)" },
                )
            }
        }
    }
}
