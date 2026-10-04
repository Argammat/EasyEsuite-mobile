package com.easyesuite.app.di

import android.content.Context
import com.easyesuite.app.BuildConfig
import com.easyesuite.app.data.SecureTokenStore
import com.easyesuite.core.ApiConfig
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

    private val _session = MutableStateFlow(tokenStore.load())
    /** Null = signed out. Observed by the root composable to switch between login and the app. */
    val session: StateFlow<Session?> = _session

    @Volatile private var graph: Graph? = _session.value?.let { Graph(it.tenant) }

    inner class Graph(val tenant: String) {
        val client = ApiClient(
            config = ApiConfig(tenant = tenant, apiRoot = BuildConfig.API_ROOT),
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

    /** Graph for the active tenant; throws if used while signed out (a programming error). */
    fun graph(): Graph = graph ?: error("No active session")

    /** A graph for a tenant the user is trying to sign in to (no session yet). */
    fun graphFor(tenant: String): Graph = Graph(tenant)

    fun onSignedIn(session: Session, graphUsed: Graph) {
        tokenStore.lastTenant = session.tenant
        tokenStore.lastEmail = session.email
        graph = graphUsed
        _session.value = session
    }

    fun signOut() {
        tokenStore.clear()
        graph = null
        _session.value = null
    }
}
