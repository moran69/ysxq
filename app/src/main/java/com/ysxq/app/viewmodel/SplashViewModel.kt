package com.ysxq.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.request.ImageRequest
import coil3.SingletonImageLoader
import com.ysxq.app.data.*
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay

data class SplashState(
    val isInitialized: Boolean = false,
    val loadingMessage: String = "正在初始化...",
    val progress: Float = 0f
)

class SplashViewModel(application: Application) : AndroidViewModel(application) {
    private val api = com.ysxq.app.data.NetworkModule.apiService
    private val cache = AppCache

    private val _state = MutableStateFlow(SplashState())
    val state: StateFlow<SplashState> = _state.asStateFlow()

    init {
        startPreload()
    }

    private fun startPreload() {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(loadingMessage = "正在连接服务器...", progress = 0.1f)

                if (cache.homeLoaded && cache.categories.isNotEmpty()) {
                    _state.value = _state.value.copy(loadingMessage = "加载完成", progress = 1f)
                    delay(300)
                    _state.value = _state.value.copy(isInitialized = true)
                    return@launch
                }

                _state.value = _state.value.copy(loadingMessage = "正在加载影片资源...", progress = 0.2f)

                val moviesDeferred = async {
                    try { api.getVideoList(ac = "detail", typeId = 6, page = 1).list.filter { it.pic.isNotBlank() }.take(12) }
                    catch (_: Exception) { emptyList() }
                }
                val tvSeriesDeferred = async {
                    try { api.getVideoList(ac = "detail", typeId = 13, page = 1).list.filter { it.pic.isNotBlank() }.take(12) }
                    catch (_: Exception) { emptyList() }
                }
                val animeDeferred = async {
                    try { api.getVideoList(ac = "detail", typeId = 30, page = 1).list.filter { it.pic.isNotBlank() }.take(12) }
                    catch (_: Exception) { emptyList() }
                }
                val varietyDeferred = async {
                    try { api.getVideoList(ac = "detail", typeId = 25, page = 1).list.filter { it.pic.isNotBlank() }.take(12) }
                    catch (_: Exception) { emptyList() }
                }
                val bannersDeferred = async {
                    try { api.getVideoList(ac = "detail", page = 1).list.filter { it.pic.isNotBlank() }.take(5) }
                    catch (_: Exception) { emptyList() }
                }
                val catDeferred = async {
                    try { api.getVideoList(ac = "list", page = 1).`class` }
                    catch (_: Exception) { emptyList() }
                }

                _state.value = _state.value.copy(loadingMessage = "正在获取影片数据...", progress = 0.4f)

                val movies = moviesDeferred.await()
                _state.value = _state.value.copy(progress = 0.55f)

                val tvSeries = tvSeriesDeferred.await()
                _state.value = _state.value.copy(progress = 0.65f)

                val anime = animeDeferred.await()
                _state.value = _state.value.copy(progress = 0.75f)

                val variety = varietyDeferred.await()
                _state.value = _state.value.copy(progress = 0.85f)

                val banners = bannersDeferred.await()
                val categories = catDeferred.await()

                _state.value = _state.value.copy(loadingMessage = "正在缓存数据...", progress = 0.9f)

                cache.saveHomeData(movies, tvSeries, anime, variety, banners)
                if (categories.isNotEmpty()) cache.saveCategories(categories)

                // Preload category page: fetch first page for all main categories
                if (categories.isNotEmpty() && !cache.categoryViewStateSaved) {
                    try {
                        val mainCategories = categories.filter { it.pid == 0 }
                        for (mainCat in mainCategories) {
                            try {
                                val subs = categories.filter { it.pid == mainCat.id }
                                val typeId = subs.firstOrNull()?.id ?: mainCat.id
                                val key = cache.videoListKey("detail", typeId, 1, "全部", "全部", null)
                                if (cache.getVideoList(key) == null) {
                                    val catVideos = api.getVideoList(ac = "detail", typeId = typeId, page = 1)
                                    cache.saveVideoList(key, AppCache.CachedResponse(
                                        catVideos.list, catVideos.pagecount, catVideos.total
                                    ))
                                }
                            } catch (_: Exception) { continue }
                        }
                    } catch (_: Exception) { }
                }

                val context = getApplication<Application>()
                val imageLoader = SingletonImageLoader.get(context)
                val allCovers = (movies + tvSeries + anime + variety + banners).map { it.pic }.filter { it.isNotBlank() }.distinct()
                allCovers.forEach { url ->
                    imageLoader.enqueue(
                        ImageRequest.Builder(context)
                            .data(url)
                            .size(400)
                            .build()
                    )
                }

                _state.value = _state.value.copy(loadingMessage = "准备就绪", progress = 1f)
                delay(500)
                _state.value = _state.value.copy(isInitialized = true)

            } catch (_: Exception) {
                _state.value = _state.value.copy(isInitialized = true)
            }
        }
    }
}
