package com.khoryz666.waypoint.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.khoryz666.waypoint.AppContainer
import com.khoryz666.waypoint.R
import com.khoryz666.waypoint.data.Settings
import com.khoryz666.waypoint.timer.Destination
import com.khoryz666.waypoint.ui.body.BodyScreen
import com.khoryz666.waypoint.ui.onboarding.OnboardingScreen
import com.khoryz666.waypoint.ui.onboarding.OnboardingViewModel
import com.khoryz666.waypoint.ui.review.ReviewScreen
import com.khoryz666.waypoint.ui.review.ReviewViewModel
import com.khoryz666.waypoint.ui.settings.ChecklistScreen
import com.khoryz666.waypoint.ui.settings.DayTypesScreen
import com.khoryz666.waypoint.ui.settings.SettingsScreen
import com.khoryz666.waypoint.ui.settings.SettingsViewModel
import com.khoryz666.waypoint.ui.today.TodayScreen
import com.khoryz666.waypoint.ui.today.TodayViewModel
import com.khoryz666.waypoint.ui.tracks.NewTrackScreen
import com.khoryz666.waypoint.ui.tracks.NewTrackViewModel
import com.khoryz666.waypoint.ui.tracks.TrackDetailScreen
import com.khoryz666.waypoint.ui.tracks.TrackDetailViewModel
import com.khoryz666.waypoint.ui.tracks.TracksScreen
import kotlinx.coroutines.flow.MutableStateFlow

/** The four bottom-nav screens (spec 5.1). */
enum class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector) {
    TODAY("today", R.string.nav_today, Icons.Filled.Home),
    TRACKS("tracks", R.string.nav_tracks, Icons.AutoMirrored.Filled.List),
    BODY("body", R.string.nav_body, Icons.Filled.Favorite),
    REVIEW("review", R.string.nav_review, Icons.Filled.DateRange),
}

@Composable
fun WaypointNavigation(c: AppContainer, destination: MutableStateFlow<Destination?>) {
    val settings by c.settings.settings.collectAsState(initial = null as Settings?)
    val s = settings ?: return
    if (!s.onboarded) {
        OnboardingScreen(viewModel { OnboardingViewModel(c) }, c)
        return
    }
    val nav = rememberNavController()
    val pending by destination.collectAsState()
    LaunchedEffect(pending) {
        when (pending) {
            Destination.REVIEW -> nav.navigate(Tab.REVIEW.route)
            Destination.DAILY_CLOSE -> nav.navigate("dailyClose")
            Destination.SETTINGS -> nav.navigate("settings")
            Destination.TODAY, Destination.INPUT, Destination.SLEEP_INPUT -> nav.navigate(Tab.TODAY.route) { popUpTo(nav.graph.findStartDestination().id) }
            null -> Unit
        }
        destination.value = null
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    Scaffold(
        bottomBar = {
            if (Tab.entries.any { it.route == route }) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            NavHost(nav, startDestination = Tab.TODAY.route) {
                composable(Tab.TODAY.route) {
                    TodayScreen(viewModel { TodayViewModel(c) }, onSettings = { nav.navigate("settings") }, onNewTrack = { nav.navigate("newTrack") }, onDailyClose = { nav.navigate("dailyClose") })
                }
                composable(Tab.TRACKS.route) { TracksScreen(c, onOpen = { nav.navigate("track/$it") }, onNew = { nav.navigate("newTrack") }) }
                composable(Tab.BODY.route) { BodyScreen(c) }
                composable(Tab.REVIEW.route) { ReviewScreen(viewModel { ReviewViewModel(c) }, onEditChecklist = { nav.navigate("checklist") }) }
                composable("settings") {
                    SettingsScreen(viewModel { SettingsViewModel(c) }, c, onBack = nav::popBackStack, onDayTypes = { nav.navigate("dayTypes") }, onChecklist = { nav.navigate("checklist") })
                }
                composable("dayTypes") { DayTypesScreen(c, onBack = nav::popBackStack) }
                composable("checklist") { ChecklistScreen(c, onBack = nav::popBackStack) }
                composable("dailyClose") { DailyCloseScreen(c, onBack = nav::popBackStack) }
                composable(
                    "newTrack?predecessor={predecessor}",
                    arguments = listOf(navArgument("predecessor") { type = NavType.LongType; defaultValue = -1L }),
                ) { entry ->
                    val predecessor = entry.arguments?.getLong("predecessor")?.takeIf { it >= 0 }
                    NewTrackScreen(viewModel { NewTrackViewModel(c, predecessor) }, onBack = nav::popBackStack, onCreated = { id ->
                        nav.popBackStack()
                        nav.navigate("track/$id")
                    })
                }
                composable("track/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    val id = entry.arguments!!.getLong("id")
                    TrackDetailScreen(
                        viewModel(key = "track-$id") { TrackDetailViewModel(c, id) },
                        onBack = nav::popBackStack,
                        onNextLevel = { nav.navigate("newTrack?predecessor=$it") },
                        onOpenTrack = { nav.navigate("track/$it") },
                    )
                }
            }
        }
    }
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
