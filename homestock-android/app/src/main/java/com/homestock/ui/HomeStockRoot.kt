package com.homestock.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.homestock.ui.navigation.BottomTab
import com.homestock.ui.navigation.Routes
import com.homestock.update.UpdateGate
import com.homestock.ui.screens.add.AddObjetScreen
import com.homestock.ui.screens.categories.CategoriesScreen
import com.homestock.ui.screens.categories.CategoryDetailScreen
import com.homestock.ui.screens.detail.ObjetDetailScreen
import com.homestock.ui.screens.search.SearchScreen
import com.homestock.ui.screens.settings.SettingsScreen
import com.homestock.ui.screens.setup.SetupScreen
import com.homestock.ui.screens.wine.WineScreen
import com.homestock.ui.screens.zones.ZoneDetailScreen
import com.homestock.ui.screens.zones.ZonesScreen

@Composable
fun HomeStockRoot(viewModel: MainViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val current = settings ?: return // brief loading flash before DataStore emits

    if (!current.setupCompleted) {
        SetupScreen()
        return
    }
    MainScaffold(viewModel)
    // Self-update prompt: silent if up to date, dialog otherwise. Placed
    // OUTSIDE the scaffold so the dialog overlays whatever route is active.
    UpdateGate()
}

@Composable
private fun MainScaffold(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.hierarchy?.firstOrNull()?.route

    val showBottomBar = BottomTab.entries.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    BottomTab.entries.forEach { tab ->
                        val selected = backStackEntry?.destination?.hierarchy
                            ?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SEARCH,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.SEARCH) {
                SearchScreen(
                    connected = connected,
                    onObjet = { navController.navigate(Routes.objetDetail(it)) },
                    onZone = { navController.navigate(Routes.zoneDetail(it)) },
                    onAdd = { navController.navigate(Routes.ADD) },
                    onAddVoice = { nom, zoneId, emp, qty ->
                        navController.navigate(Routes.addPrefill(nom, zoneId, emp, qty))
                    },
                    onRetrySync = viewModel::retrySync,
                )
            }
            composable(Routes.ZONES) {
                ZonesScreen(
                    connected = connected,
                    onZone = { navController.navigate(Routes.zoneDetail(it)) },
                    onWine = { navController.navigate(Routes.WINE) },
                )
            }
            composable(Routes.CATEGORIES) {
                CategoriesScreen(
                    onCategory = { navController.navigate(Routes.categoryDetail(it)) },
                    onWine = { navController.navigate(Routes.WINE) },
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen() }

            composable(
                route = "${Routes.ADD}?nom={nom}&zoneId={zoneId}&emp={emp}&qty={qty}",
                arguments = listOf(
                    navArgument("nom") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("zoneId") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("emp") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("qty") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) {
                AddObjetScreen(onDone = { navController.popBackStack() })
            }
            composable(Routes.ZONE_DETAIL) {
                ZoneDetailScreen(
                    onBack = { navController.popBackStack() },
                    onObjet = { navController.navigate(Routes.objetDetail(it)) },
                    connected = connected,
                )
            }
            composable(Routes.CATEGORY_DETAIL) {
                CategoryDetailScreen(
                    onBack = { navController.popBackStack() },
                    onObjet = { navController.navigate(Routes.objetDetail(it)) },
                    connected = connected,
                )
            }
            composable(Routes.OBJET_DETAIL) {
                ObjetDetailScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate(Routes.editObjet(it)) },
                    onZone = { navController.navigate(Routes.zoneDetail(it)) },
                    onCategory = { navController.navigate(Routes.categoryDetail(it)) },
                    connected = connected,
                )
            }
            composable(Routes.OBJET_EDIT) {
                // Reuses the add stepper in edit mode (localId nav arg).
                AddObjetScreen(onDone = { navController.popBackStack() })
            }
            composable(Routes.WINE) {
                WineScreen(
                    onBack = { navController.popBackStack() },
                    onObjet = { navController.navigate(Routes.objetDetail(it)) },
                    connected = connected,
                )
            }
        }
    }
}
