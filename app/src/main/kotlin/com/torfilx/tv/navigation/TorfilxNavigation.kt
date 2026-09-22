package com.torfilx.tv.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.torfilx.core.model.PlayAction
import com.torfilx.feature.details.DetailsScreen
import com.torfilx.feature.details.DetailsViewModel
import com.torfilx.feature.home.HomeScreen
import com.torfilx.feature.library.LibraryMode
import com.torfilx.feature.library.LibraryScreen
import com.torfilx.feature.library.LibraryViewModel
import com.torfilx.feature.player.PlayerScreen
import com.torfilx.feature.player.PlayerViewModel
import com.torfilx.feature.search.SearchScreen
import com.torfilx.feature.settings.SettingsScreen

/** Navigation destinations. Route strings are centralised so no screen builds a URL by hand. */
object Routes {
    const val HOME = "home"
    const val MOVIES = "movies"
    const val SHOWS = "shows"
    const val MY_LIST = "my-list"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val DETAILS = "details/{${DetailsViewModel.ARG_ITEM_ID}}"
    const val PLAYER =
        "player/{${PlayerViewModel.ARG_PLAYABLE_ID}}?startPositionMs={${PlayerViewModel.ARG_START_POSITION}}" +
            "&sourceId={${PlayerViewModel.ARG_SOURCE_ID}}"

    fun details(itemId: String) = "details/$itemId"

    fun player(
        playableId: String,
        startPositionMs: Long? = null,
        sourceId: String? = null,
    ) = buildString {
        append("player/")
        append(playableId)
        append("?startPositionMs=")
        append(startPositionMs ?: -1L)
        append("&sourceId=")
        append(sourceId.orEmpty())
    }

    /** The tab destinations that the top bar switches between. */
    val TOP_LEVEL = setOf(HOME, MOVIES, SHOWS, MY_LIST, SEARCH, SETTINGS)
}

private const val TRANSITION_MS = 200

@Composable
fun TorfilxNavHost(
    navController: NavHostController,
    onExitApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openDetails: (String) -> Unit = { itemId -> navController.navigate(Routes.details(itemId)) }

    /**
     * Playing from a card or the hero opens the film with no explicit source, so the player picks
     * the best available quality; the details screen is where a specific quality can be chosen.
     */
    val play: (PlayAction) -> Unit = { action ->
        when (action) {
            PlayAction.Unavailable -> Unit
            is PlayAction.Play -> navController.navigate(Routes.player(action.itemId, 0L))
            is PlayAction.Resume ->
                navController.navigate(Routes.player(action.itemId, action.positionMs))
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
        enterTransition = { fadeIn(tween(TRANSITION_MS)) },
        exitTransition = { fadeOut(tween(TRANSITION_MS)) },
        popEnterTransition = { fadeIn(tween(TRANSITION_MS)) },
        popExitTransition = { fadeOut(tween(TRANSITION_MS)) },
    ) {
        composable(Routes.HOME) {
            HomeScreen(onOpenDetails = openDetails, onPlay = play)
        }

        // The three grids share one screen. Each passes its mode as a route argument so the view model
        // has it from its first frame: set any later, the Shows tab would draw the films for a moment.
        listOf(
            Routes.MOVIES to LibraryMode.MOVIES,
            Routes.SHOWS to LibraryMode.SHOWS,
            Routes.MY_LIST to LibraryMode.MY_LIST,
        ).forEach { (route, mode) ->
            composable(
                route = route,
                arguments = listOf(
                    navArgument(LibraryViewModel.ARG_MODE) {
                        type = NavType.StringType
                        defaultValue = mode.name
                    },
                ),
            ) {
                LibraryScreen(
                    mode = mode,
                    onOpenDetails = openDetails,
                    onBack = { navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) } },
                )
            }
        }

        composable(Routes.SEARCH) {
            SearchScreen(
                onOpenDetails = openDetails,
                onBack = { navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) } },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) } },
            )
        }

        composable(
            route = Routes.DETAILS,
            arguments = listOf(
                navArgument(DetailsViewModel.ARG_ITEM_ID) { type = NavType.StringType },
            ),
        ) {
            DetailsScreen(
                // A film's chosen quality, a show's next-up episode, or an episode from the list.
                // No start position: the player resumes from what is stored, as the button said.
                onPlay = { playableId, sourceId ->
                    navController.navigate(Routes.player(playableId, sourceId = sourceId))
                },
                onBack = { if (!navController.popBackStack()) onExitApp() },
            )
        }

        composable(
            route = Routes.PLAYER,
            arguments = listOf(
                navArgument(PlayerViewModel.ARG_PLAYABLE_ID) { type = NavType.StringType },
                navArgument(PlayerViewModel.ARG_START_POSITION) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument(PlayerViewModel.ARG_SOURCE_ID) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
            // The player never animates in: a cross-fade over a video surface flashes.
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
        ) {
            PlayerScreen(
                onExit = { if (!navController.popBackStack()) onExitApp() },
            )
        }
    }
}
