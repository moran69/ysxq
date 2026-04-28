package com.ysxq.app.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.ysxq.app.data.auth.AuthRepository
import com.ysxq.app.data.auth.AuthState
import com.ysxq.app.data.local.favoritesStore
import com.ysxq.app.data.local.userPreferences
import com.ysxq.app.data.local.watchHistoryStore
import com.ysxq.app.data.storage.CloudBaseStorageHelper
import com.ysxq.app.data.sync.FavoritesSyncRepository

import com.ysxq.app.data.sync.WatchHistorySyncRepository
import com.ysxq.app.ui.screens.*
import com.ysxq.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun triggerCloudSync() {
        if (!AuthRepository.isLoggedIn) return
        scope.launch {
            try {
                val favStore = context.favoritesStore()
                val histStore = context.watchHistoryStore()
                withContext(Dispatchers.IO) {
                    FavoritesSyncRepository(favStore).pullFromCloud()
                    WatchHistorySyncRepository(histStore).pullFromCloud()
                }
            } catch (_: Exception) { }
        }
    }

    LaunchedEffect(Unit) {
        var wasAuthenticated = AuthRepository.isLoggedIn
        AuthRepository.authStateChanges().collect { state ->
            if (wasAuthenticated && state is AuthState.Unauthenticated) {
                val current = currentRoute
                if (current != null && current != Screen.Auth.route && current != Screen.Splash.route) {
                    navController.navigate(Screen.Auth.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
            if (state is AuthState.Authenticated) {
                val photoUrl = state.user.photoUrl
                if (!photoUrl.isNullOrBlank() && photoUrl.startsWith("cloud://")) {
                    launch(Dispatchers.IO) {
                        val resolved = CloudBaseStorageHelper.resolveAvatarUrl(photoUrl)
                        if (resolved != null) {
                            context.userPreferences().saveResolvedAvatarUrl(resolved)
                        }
                    }
                }
            }
            wasAuthenticated = state is AuthState.Authenticated
        }
    }

    val bottomBarTabs = listOf(Screen.Home, Screen.Category, Screen.Download, Screen.Profile)
    val showBottomBar = currentRoute in bottomBarTabs.map { it.route }
    val showSplash = currentRoute == Screen.Splash.route

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (showBottomBar && !showSplash) {
                NavigationBar(
                    containerColor = DarkSurface,
                    tonalElevation = 8.dp
                ) {
                    NavigationBarItem(
                        icon = {
                            Icon(
                                if (currentRoute == Screen.Home.route) Icons.Filled.Home
                                else Icons.Outlined.Home,
                                contentDescription = null
                            )
                        },
                        label = { Text("首页", fontSize = 11.sp) },
                        selected = currentRoute == Screen.Home.route,
                        onClick = {
                            if (currentRoute != Screen.Home.route) {
                                navController.navigate(Screen.Home.route) {
                                    popUpTo(Screen.Home.route) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SakuraPrimary,
                            selectedTextColor = SakuraPrimary,
                            unselectedIconColor = TextTertiary,
                            unselectedTextColor = TextTertiary,
                            indicatorColor = SakuraPrimary.copy(alpha = 0.15f)
                        )
                    )
                    NavigationBarItem(
                        icon = {
                            Icon(
                                if (currentRoute == Screen.Category.route) Icons.Filled.VideoLibrary
                                else Icons.Outlined.VideoLibrary,
                                contentDescription = null
                            )
                        },
                        label = { Text("分类", fontSize = 11.sp) },
                        selected = currentRoute == Screen.Category.route,
                        onClick = {
                            if (currentRoute != Screen.Category.route) {
                                navController.navigate(Screen.Category.createRoute()) {
                                    popUpTo(Screen.Home.route) { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SakuraPrimary,
                            selectedTextColor = SakuraPrimary,
                            unselectedIconColor = TextTertiary,
                            unselectedTextColor = TextTertiary,
                            indicatorColor = SakuraPrimary.copy(alpha = 0.15f)
                        )
                    )
                    NavigationBarItem(
                        icon = {
                            Icon(
                                if (currentRoute == Screen.Download.route) Icons.Filled.PlayCircle
                                else Icons.Filled.PlayCircle,
                                contentDescription = null
                            )
                        },
                        label = { Text("下载", fontSize = 11.sp) },
                        selected = currentRoute == Screen.Download.route,
                        onClick = {
                            if (currentRoute != Screen.Download.route) {
                                navController.navigate(Screen.Download.route) {
                                    popUpTo(Screen.Home.route) { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SakuraPrimary,
                            selectedTextColor = SakuraPrimary,
                            unselectedIconColor = TextTertiary,
                            unselectedTextColor = TextTertiary,
                            indicatorColor = SakuraPrimary.copy(alpha = 0.15f)
                        )
                    )
                    NavigationBarItem(
                        icon = {
                            Icon(
                                if (currentRoute == Screen.Profile.route) Icons.Filled.Person
                                else Icons.Outlined.Person,
                                contentDescription = null
                            )
                        },
                        label = { Text("我的", fontSize = 11.sp) },
                        selected = currentRoute == Screen.Profile.route,
                        onClick = {
                            if (currentRoute != Screen.Profile.route) {
                                navController.navigate(Screen.Profile.route) {
                                    popUpTo(Screen.Home.route) { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SakuraPrimary,
                            selectedTextColor = SakuraPrimary,
                            unselectedIconColor = TextTertiary,
                            unselectedTextColor = TextTertiary,
                            indicatorColor = SakuraPrimary.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Splash.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { fadeIn(tween(200)) },
            exitTransition = { fadeOut(tween(200)) },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) {
            composable(Screen.Splash.route) {
                SplashScreen(
                    onInitialized = {
                        if (AuthRepository.isLoggedIn) {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Splash.route) { inclusive = true }
                            }
                        } else {
                            val isGuest = runCatching {
                                kotlinx.coroutines.runBlocking {
                                    context.userPreferences().isGuest.first()
                                }
                            }.getOrDefault(false)
                            if (isGuest) {
                                navController.navigate(Screen.Home.route) {
                                    popUpTo(Screen.Splash.route) { inclusive = true }
                                }
                            } else {
                                navController.navigate(Screen.Auth.route) {
                                    popUpTo(Screen.Splash.route) { inclusive = true }
                                }
                            }
                        }
                    }
                )
            }

            composable(Screen.Auth.route) {
                AuthScreen(
                    onAuthSuccess = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Auth.route) { inclusive = true }
                        }
                        triggerCloudSync()
                    }
                )
            }

            composable(Screen.Home.route) {
                HomeScreen(
                    onVideoClick = { videoId ->
                        navController.navigate(Screen.Detail.createRoute(videoId))
                    },
                    onCategoryMore = { categoryId ->
                        navController.navigate(Screen.Category.createRoute(categoryId))
                    },
                    onSearchClick = {
                        navController.navigate(Screen.Search.route)
                    }
                )
            }

            composable(
                Screen.Category.route,
                arguments = listOf(
                    navArgument("categoryId") {
                        type = NavType.IntType
                        defaultValue = 0
                    }
                )
            ) { entry ->
                val categoryId = entry.arguments?.getInt("categoryId") ?: 0
                CategoryScreen(
                    onVideoClick = { videoId ->
                        navController.navigate(Screen.Detail.createRoute(videoId))
                    },
                    initialCategoryId = categoryId
                )
            }

            composable(Screen.Search.route) {
                SearchScreen(
                    onVideoClick = { videoId ->
                        navController.navigate(Screen.Detail.createRoute(videoId))
                    },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                Screen.Detail.route,
                arguments = listOf(
                    navArgument("videoId") { type = NavType.IntType }
                ),
                exitTransition = { fadeOut(tween(0)) },
                popExitTransition = { fadeOut(tween(0)) }
            ) { entry ->
                val videoId = entry.arguments?.getInt("videoId") ?: return@composable Box {}
                DetailScreen(
                    videoId = videoId,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Profile.route) {
                ProfileScreen(
                    onLoginClick = {
                        navController.navigate(Screen.Auth.route) {
                            popUpTo(Screen.Home.route) { inclusive = false }
                        }
                    },
                    onFavoritesClick = {
                        navController.navigate(Screen.Favorites.route)
                    },
                    onWatchHistoryClick = {
                        navController.navigate(Screen.WatchHistory.route)
                    },
                    onProfileEditClick = {
                        navController.navigate(Screen.ProfileEdit.route)
                    },
                    onFeedback = {
                        navController.navigate(Screen.Feedback.route)
                    },
                    onAbout = {
                        navController.navigate(Screen.About.route)
                    }
                )
            }

            composable(Screen.Favorites.route) {
                FavoritesScreen(
                    onBack = { navController.popBackStack() },
                    onVideoClick = { videoId ->
                        navController.navigate(Screen.Detail.createRoute(videoId))
                    }
                )
            }

            composable(Screen.WatchHistory.route) {
                WatchHistoryScreen(
                    onBack = { navController.popBackStack() },
                    onVideoClick = { videoId ->
                        navController.navigate(Screen.Detail.createRoute(videoId))
                    }
                )
            }

            composable(Screen.Download.route) {
                DownloadScreen(
                    onBack = { navController.popBackStack() },
                    onPlayLocal = { videoId, episodeIndex ->
                        navController.navigate(Screen.LocalPlayer.createRoute(videoId, episodeIndex))
                    }
                )
            }

            composable(
                Screen.LocalPlayer.route,
                arguments = listOf(
                    navArgument("videoId") { type = NavType.IntType },
                    navArgument("episodeIndex") { type = NavType.IntType; defaultValue = 0 }
                )
            ) { entry ->
                val videoId = entry.arguments?.getInt("videoId") ?: return@composable
                val episodeIndex = entry.arguments?.getInt("episodeIndex") ?: 0
                LocalPlayerScreen(
                    videoId = videoId,
                    initialEpisodeIndex = episodeIndex,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.ProfileEdit.route) {
                ProfileEditScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Feedback.route) {
                FeedbackScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.About.route) {
                AboutScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
