package com.momo.app.ui.nav

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Home : Screen("home")
    data object Susou : Screen("susou")
    data object KanjuAi : Screen("kanjuai")
    data object Category : Screen("category?categoryId={categoryId}") {
        fun createRoute(categoryId: Int = 0) = "category?categoryId=$categoryId"
    }
    data object Search : Screen("search")
    data object Detail : Screen("detail/{videoId}?susou={susou}&kanjuai={kanjuai}") {
        fun createRoute(videoId: Int, susou: Boolean = false, kanjuai: Boolean = false) =
            "detail/$videoId?susou=${if (susou) 1 else 0}&kanjuai=${if (kanjuai) 1 else 0}"
    }
    data object Profile : Screen("profile")
    data object Auth : Screen("auth")
    data object Favorites : Screen("favorites")
    data object WatchHistory : Screen("watch_history")
    data object Download : Screen("download")
    data object LocalPlayer : Screen("local_player/{videoId}?episodeIndex={episodeIndex}") {
        fun createRoute(videoId: Int, episodeIndex: Int = 0) = "local_player/$videoId?episodeIndex=$episodeIndex"
    }
    data object ProfileEdit : Screen("profile_edit")
    data object Feedback : Screen("feedback")
    data object About : Screen("about")
}
