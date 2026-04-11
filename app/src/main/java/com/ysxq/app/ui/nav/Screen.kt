package com.ysxq.app.ui.nav

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Home : Screen("home")
    data object Category : Screen("category?categoryId={categoryId}") {
        fun createRoute(categoryId: Int = 0) = "category?categoryId=$categoryId"
    }
    data object Search : Screen("search")
    data object Detail : Screen("detail/{videoId}") {
        fun createRoute(videoId: Int) = "detail/$videoId"
    }
    data object Profile : Screen("profile")
    data object Auth : Screen("auth")
    data object Favorites : Screen("favorites")
    data object WatchHistory : Screen("watch_history")
    data object ProfileEdit : Screen("profile_edit")
    data object Feedback : Screen("feedback")
    data object About : Screen("about")
}
