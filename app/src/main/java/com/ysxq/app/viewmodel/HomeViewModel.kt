package com.ysxq.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ysxq.app.data.*
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll

data class HomeState(
    val isLoading: Boolean = false,
    val bannerVideos: List<VideoItem> = emptyList(),
    val movies: List<VideoItem> = emptyList(),
    val tvSeries: List<VideoItem> = emptyList(),
    val anime: List<VideoItem> = emptyList(),
    val variety: List<VideoItem> = emptyList(),
    val categories: List<VideoCategory> = emptyList(),
    val error: String? = null
)

class HomeViewModel : ViewModel() {
    private val api = com.ysxq.app.data.NetworkModule.apiService
    private val cache = AppCache

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        loadHome()
    }

    fun loadHome(forceRefresh: Boolean = false) {
        // ViewModel 被重建后 _state 为空，但 cache 有数据 → 直接恢复
        if (!forceRefresh && cache.homeLoaded) {
            val s = _state.value
            if (s.bannerVideos.isEmpty()) {
                _state.value = s.copy(
                    movies = cache.homeMovies,
                    tvSeries = cache.homeTvSeries,
                    anime = cache.homeAnime,
                    variety = cache.homeVariety,
                    bannerVideos = cache.homeBanners,
                    categories = cache.categories,
                    isLoading = false
                )
            }
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val videoDeferreds = listOf(
                    async {
                        try { api.getVideoList(ac = "detail", typeId = 6, page = 1).list.filter { it.pic.isNotBlank() }.take(12) }
                        catch (_: Exception) { emptyList() }
                    },
                    async {
                        try { api.getVideoList(ac = "detail", typeId = 13, page = 1).list.filter { it.pic.isNotBlank() }.take(12) }
                        catch (_: Exception) { emptyList() }
                    },
                    async {
                        try { api.getVideoList(ac = "detail", typeId = 30, page = 1).list.filter { it.pic.isNotBlank() }.take(12) }
                        catch (_: Exception) { emptyList() }
                    },
                    async {
                        try { api.getVideoList(ac = "detail", typeId = 25, page = 1).list.filter { it.pic.isNotBlank() }.take(12) }
                        catch (_: Exception) { emptyList() }
                    },
                    async {
                        try { api.getVideoList(ac = "detail", page = 1).list.filter { it.pic.isNotBlank() }.take(5) }
                        catch (_: Exception) { emptyList() }
                    }
                )
                val catDeferred = async {
                    try {
                        val resp = api.getVideoList(ac = "list", page = 1)
                        resp.`class`
                    } catch (_: Exception) { emptyList() }
                }

                videoDeferreds.awaitAll()
                val movies = videoDeferreds[0].await()
                val tvSeries = videoDeferreds[1].await()
                val anime = videoDeferreds[2].await()
                val variety = videoDeferreds[3].await()
                val banners = videoDeferreds[4].await()
                val categories = catDeferred.await()

                cache.saveHomeData(movies, tvSeries, anime, variety, banners)
                if (categories.isNotEmpty()) cache.saveCategories(categories)

                _state.value = _state.value.copy(
                    movies = movies,
                    tvSeries = tvSeries,
                    anime = anime,
                    variety = variety,
                    bannerVideos = banners,
                    categories = categories,
                    isLoading = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "加载失败")
            }
        }
    }
}
