package com.easyesuite.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.easyesuite.app.di.AppContainer
import com.easyesuite.app.ui.assistant.AssistantScreen
import com.easyesuite.app.ui.auth.LoginScreen
import com.easyesuite.app.ui.auth.LoginViewModel
import com.easyesuite.app.ui.dashboard.DashboardScreen
import com.easyesuite.app.ui.inventory.AdjustScreen
import com.easyesuite.app.ui.inventory.InventoryScreen
import com.easyesuite.app.ui.inventory.NewTransferScreen
import com.easyesuite.app.ui.inventory.ReceiveScreen
import com.easyesuite.app.ui.inventory.TransfersScreen
import com.easyesuite.app.ui.items.ItemDetailScreen
import com.easyesuite.app.ui.items.ItemsScreen
import com.easyesuite.app.ui.items.NewItemScreen
import com.easyesuite.app.ui.more.MoreScreen
import com.easyesuite.app.ui.more.SettingsScreen
import com.easyesuite.app.ui.orders.OrderDetailScreen
import com.easyesuite.app.ui.orders.OrdersScreen
import com.easyesuite.app.ui.orders.ShipmentDetailScreen
import com.easyesuite.app.ui.reports.ReportsScreen
import com.easyesuite.app.ui.scan.ScanResultScreen
import com.easyesuite.app.ui.scan.ScannerScreen

object Routes {
    const val HOME = "home"
    const val ITEMS = "items"
    const val INVENTORY = "inventory"
    const val ORDERS = "orders"
    const val MORE = "more"

    const val ITEM_DETAIL = "item/{id}?type={type}"
    const val ITEM_NEW = "item/new?upc={upc}"
    const val ORDER_DETAIL = "order/{id}"
    const val SHIPMENT_DETAIL = "shipment/{id}"
    const val SCANNER = "scan?mode={mode}"
    const val SCAN_RESULT = "scan/result/{code}"
    const val TRANSFERS = "transfers"
    const val TRANSFER_NEW = "transfers/new?item={item}"
    const val RECEIVE = "receive"
    const val ADJUST = "adjust?item={item}&warehouse={warehouse}"
    const val REPORTS = "reports"
    const val ASSISTANT = "assistant"
    const val SETTINGS = "settings"

    /** `type` is the row's `item_type` (INV/KIT/VAR) so the detail screen can hit the type-specific endpoint. */
    fun item(id: Long, type: String? = null) = "item/$id" + (type?.takeIf { it.isNotBlank() }?.let { "?type=${android.net.Uri.encode(it)}" } ?: "")
    fun newItem(upc: String? = null) = "item/new" + (upc?.let { "?upc=$it" } ?: "")
    fun order(id: Long) = "order/$id"
    fun shipment(id: String) = "shipment/$id"
    fun scanner(mode: String = "any") = "scan?mode=$mode"
    fun scanResult(code: String) = "scan/result/${android.net.Uri.encode(code)}"
    fun newTransfer(itemId: Long? = null) = "transfers/new" + (itemId?.let { "?item=$it" } ?: "")
    fun adjust(itemId: Long? = null, warehouseId: Int? = null) =
        "adjust?item=${itemId ?: -1}&warehouse=${warehouseId ?: -1}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Dashboard", Icons.Default.Dashboard),
    Tab(Routes.ITEMS, "Items", Icons.Default.Inventory2),
    Tab(Routes.INVENTORY, "Inventory", Icons.Default.Warehouse),
    Tab(Routes.ORDERS, "Ecommerce", Icons.Default.ShoppingCart),
    Tab(Routes.MORE, "More", Icons.Default.MoreHoriz),
)

@Composable
fun AppRoot(container: AppContainer) {
    val session by container.session.collectAsState()
    if (session == null) {
        val vm: LoginViewModel = viewModel(key = "login") { LoginViewModel(container) }
        LoginScreen(vm)
    } else {
        // Re-create the whole nav graph when the tenant changes so no ViewModel outlives its session.
        key(session!!.tenant) { MainScaffold(container) }
    }
}

@Composable
private fun MainScaffold(container: AppContainer) {
    val graph = remember { container.graph() }   // captured once; MainScaffold is re-keyed per tenant
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = tabs.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBar) NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        AppNavHost(nav, graph, container, Modifier.padding(padding))
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, graph: AppContainer.Graph, container: AppContainer, modifier: Modifier) {
    NavHost(nav, startDestination = Routes.HOME, modifier = modifier) {
        composable(Routes.HOME) { DashboardScreen(graph, nav) }
        composable(Routes.ITEMS) { ItemsScreen(graph, nav) }
        composable(Routes.INVENTORY) { InventoryScreen(graph, nav) }
        composable(Routes.ORDERS) { OrdersScreen(graph, nav) }
        composable(Routes.MORE) { MoreScreen(graph, nav, container) }

        composable(
            Routes.ITEM_DETAIL,
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("type") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) {
            ItemDetailScreen(graph, nav, it.arguments!!.getLong("id"), it.arguments?.getString("type"))
        }
        composable(Routes.ITEM_NEW, arguments = listOf(navArgument("upc") { type = NavType.StringType; nullable = true; defaultValue = null })) {
            NewItemScreen(graph, nav, it.arguments?.getString("upc"))
        }
        composable(Routes.ORDER_DETAIL, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            OrderDetailScreen(graph, nav, it.arguments!!.getLong("id"))
        }
        composable(Routes.SHIPMENT_DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            ShipmentDetailScreen(graph, nav, it.arguments!!.getString("id")!!)
        }
        composable(Routes.SCANNER, arguments = listOf(navArgument("mode") { type = NavType.StringType; defaultValue = "any" })) {
            ScannerScreen(
                mode = it.arguments?.getString("mode") ?: "any",
                onBack = { nav.popBackStack() },
                onCode = { code ->
                    // Hand the code back to whoever opened the scanner (e.g. the pack-verify screen) if they asked for it.
                    nav.previousBackStackEntry?.savedStateHandle?.set("scanned_code", code)
                    val mode = it.arguments?.getString("mode") ?: "any"
                    if (mode == "return") nav.popBackStack()
                    else nav.navigate(Routes.scanResult(code)) { popUpTo(Routes.SCANNER) { inclusive = true } }
                },
            )
        }
        composable(Routes.SCAN_RESULT, arguments = listOf(navArgument("code") { type = NavType.StringType })) {
            ScanResultScreen(graph, nav, it.arguments!!.getString("code")!!)   // Navigation already URL-decodes path args
        }
        composable(Routes.TRANSFERS) { TransfersScreen(graph, nav) }
        composable(Routes.TRANSFER_NEW, arguments = listOf(navArgument("item") { type = NavType.LongType; defaultValue = -1L })) {
            NewTransferScreen(graph, nav, it.arguments?.getLong("item")?.takeIf { id -> id > 0 })
        }
        composable(Routes.RECEIVE) { ReceiveScreen(graph, nav) }
        composable(
            Routes.ADJUST,
            arguments = listOf(
                navArgument("item") { type = NavType.LongType; defaultValue = -1L },
                navArgument("warehouse") { type = NavType.IntType; defaultValue = -1 },
            ),
        ) {
            AdjustScreen(
                graph, nav,
                it.arguments?.getLong("item")?.takeIf { id -> id > 0 },
                it.arguments?.getInt("warehouse")?.takeIf { id -> id > 0 },
            )
        }
        composable(Routes.REPORTS) { ReportsScreen(graph, nav) }
        composable(Routes.ASSISTANT) { AssistantScreen(graph, nav) }
        composable(Routes.SETTINGS) { SettingsScreen(graph, nav, container) }
    }
}
