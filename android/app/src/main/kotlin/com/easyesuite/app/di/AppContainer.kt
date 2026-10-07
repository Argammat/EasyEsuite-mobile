package com.easyesuite.app.di

import android.content.Context
import com.easyesuite.app.BuildConfig
import com.easyesuite.app.data.SecureTokenStore
import com.easyesuite.core.ApiConfig
import com.easyesuite.core.FirebaseConfig
import com.easyesuite.core.auth.AuthService
import com.easyesuite.core.auth.Session
import com.easyesuite.core.net.ApiClient
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
 * Hand-rolled DI: one graph per signed-in tenant. The API client is rebuilt when the tenant
 * changes (login/logout) because the tenant is part of every URL.
 */
class AppContainer(context: Context) {

    val tokenStore = SecureTokenStore(context.applicationContext)

    private val okHttp: OkHttpClient = OkHttpClient.Builder().apply {
        if (BuildConfig.DEBUG) {
            addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        }
    }.build()

    /** True when this build signs in against Firebase / Identity Platform (see app/build.gradle.kts). */
    val firebaseSignIn: Boolean get() = BuildConfig.FIREBASE_API_KEY.isNotBlank()

    /** A session whose workspace is still to be chosen never counts as signed in (and is dropped on launch). */
    private val _session = MutableStateFlow(restoredSession())
    /** Null = signed out. Observed by the root composable to switch between login and the app. */
    val session: StateFlow<Session?> = _session

    /** Set by [switchWorkspace]: the login screen opens straight on the workspace picker with these tokens. */
    @Volatile var pendingSwitch: Session? = null
        private set

    @Volatile private var graph: Graph? = _session.value?.let { Graph(it.tenant, it.firebaseTenantId) }

    inner class Graph(val tenant: String, firebaseTenantId: String? = null) {
        val config = ApiConfig(
            tenant = tenant,
            apiRoot = BuildConfig.API_ROOT,
            firebase = BuildConfig.FIREBASE_API_KEY.takeIf { it.isNotBlank() }?.let { key ->
                FirebaseConfig(apiKey = key, tenantId = firebaseTenantId ?: BuildConfig.FIREBASE_TENANT_ID.takeIf { it.isNotBlank() })
            },
        )
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
    }

    private fun restoredSession(): Session? {
        val stored = tokenStore.load() ?: return null
        if (stored.isPending) { tokenStore.clear(); return null }
        return stored
    }

    /** Graph for the active tenant; throws if used while signed out (a programming error). */
    fun graph(): Graph = graph ?: error("No active session")

    /**
     * A graph for signing in. With a blank tenant the auth calls go to the global root (email + password first,
     * workspace afterwards); with a tenant they are scoped to that workspace.
     */
    fun graphFor(tenant: String = "", firebaseTenantId: String? = null): Graph = Graph(tenant, firebaseTenantId)

    fun onSignedIn(session: Session) {
        tokenStore.lastTenant = session.tenant
        tokenStore.lastEmail = session.email
        pendingSwitch = null
        graph = Graph(session.tenant, session.firebaseTenantId)
        _session.value = session
    }

    /** Keep the tokens, drop the workspace: the login screen shows the picker again. */
    fun switchWorkspace() {
        val current = _session.value ?: return
        pendingSwitch = current.copy(tenant = "")
        graph = null
        _session.value = null
    }

    /** Backing out of a workspace switch: restore the previous workspace without re-authenticating. */
    fun cancelWorkspaceSwitch() {
        val pending = pendingSwitch ?: return
        val previous = tokenStore.lastTenant ?: return
        pendingSwitch = null
        onSignedIn(pending.copy(tenant = previous))
    }

    fun signOut() {
        tokenStore.clear()
        pendingSwitch = null
        graph = null
        _session.value = null
    }
}
