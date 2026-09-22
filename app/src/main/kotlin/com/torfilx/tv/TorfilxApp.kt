package com.torfilx.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.torfilx.core.ui.component.TopNavItem
import com.torfilx.core.ui.component.TopNavBar
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.tv.navigation.TorfilxNavHost
import com.torfilx.tv.navigation.Routes

private val NAV_ITEMS = listOf(
    TopNavItem(Routes.HOME, "Home"),
    TopNavItem(Routes.MOVIES, "Movies"),
    TopNavItem(Routes.SHOWS, "Shows"),
    TopNavItem(Routes.MY_LIST, "My List"),
    TopNavItem(Routes.SEARCH, "Search"),
    TopNavItem(Routes.SETTINGS, "Settings"),
)

/**
 * App scaffold: the top tab bar plus the nav host.
 *
 * The bar is hidden on Details and the Player so those screens get the whole 16:9 canvas, which is
 * what makes them feel cinematic rather than like a web page with a header.
 */
@Composable
fun TorfilxApp(
    onExitApp: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showNavBar = currentRoute in Routes.TOP_LEVEL
    val dimens = LocalTorfilxDimens.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TorfilxColors.Background),
    ) {
        if (showNavBar) {
            TopNavBar(
                items = NAV_ITEMS,
                selectedId = currentRoute ?: Routes.HOME,
                onSelect = { item ->
                    if (item.id != currentRoute) {
                        navController.navigate(item.id) {
                            // Tabs are siblings, not a stack: switching tabs must not grow the back
                            // stack, and Back from any tab returns to Home (then exits).
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                modifier = Modifier.padding(top = dimens.overscanVertical),
            )
        }

        TorfilxNavHost(
            navController = navController,
            onExitApp = onExitApp,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
