package com.momo.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.request.ImageRequest
import coil3.SingletonImageLoader
import com.momo.app.data.*
import com.momo.app.data.update.AppVersionInfo
import com.momo.app.data.update.UpdateChecker
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
    val progress: Float = 0f,
    val updateAvailable: AppVersionInfo? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f
)

class SplashViewModel(application: Application) : AndroidViewModel(application) {
    private val api = com.momo.app.data.NetworkModule.apiService
    private val cache = AppCache

    private val _state = MutableStateFlow(SplashState())
    val state: StateFlow<SplashState> = _state.asStateFlow()

    init {
        startPreload()
    }

    private fun startPreload() {
        viewModelScope.launch {
            try {
                // 更新检查由 App.autoCheck() 全局负责（发现新版本弹统一弹窗），
                // 启动页不再自查，避免与全局弹窗同时出现两个更新窗口
                _state.value = _state.value.copy(loadingMessage = "正在连接服务器...", progress = 0.1f)

                if (cache.homeLoaded && cache.categories.isNotEmpty()) {
                    cache.loadSearchIndexFromFile()
                    cache.startBackgroundIndexIfNeeded()
                    _state.value = _state.value.copy(loadingMessage = "加载完成", progress = 1f)
                    delay(300)
                    _state.value = _state.value.copy(isInitialized = true)
                    return@launch
                }

                _state.value = _state.value.copy(loadingMessage = "正在加载影片资源...", progress = 0.2f)

                val moviesDeferred = async {
                    try { api.getVideoList(ac = "detail", typeId = 23, page = 1).list.filter { it.pic.isNotBlank() }.take(12) }
                    catch (_: Exception) { emptyList() }
                }
                val tvSeriesDeferred = async {
                    try { api.getVideoList(ac = "detail", typeId = 30, page = 1).list.filter { it.pic.isNotBlank() }.take(12) }
                    catch (_: Exception) { emptyList() }
                }
                val animeDeferred = async {
                    try { api.getVideoList(ac = "detail", typeId = 43, page = 1).list.filter { it.pic.isNotBlank() }.take(12) }
                    catch (_: Exception) { emptyList() }
                }
                val varietyDeferred = async {
                    try { api.getVideoList(ac = "detail", typeId = 39, page = 1).list.filter { it.pic.isNotBlank() }.take(12) }
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

                // 将首页视频加入搜索索引
                cache.addToSearchIndex(movies + tvSeries + anime + variety + banners)

                // Preload category page: parallel fetch first page for all main categories
                if (categories.isNotEmpty() && !cache.categoryViewStateSaved) {
                    try {
                        val mainCategories = categories.filter { it.pid == 0 }

                        // Parallel preload all main categories
                        val results = kotlinx.coroutines.coroutineScope {
                            mainCategories.map { mainCat ->
                                async {
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
                                        val cached = cache.getVideoList(key)
                                        mainCat.id to (cached?.total ?: 0)
                                    } catch (_: Exception) {
                                        mainCat.id to -1
                                    }
                                }
                            }.awaitAll()
                        }

                        // 将分类页视频加入搜索索引
                        for (mainCat in categories.filter { it.pid == 0 }) {
                            val subs = categories.filter { it.pid == mainCat.id }
                            val typeId = subs.firstOrNull()?.id ?: mainCat.id
                            val key = cache.videoListKey("detail", typeId, 1, "全部", "全部", null)
                            cache.getVideoList(key)?.list?.let { cache.addToSearchIndex(it) }
                        }

                        // Filter out main categories with 0 videos
                        val validMainIds = results
                            .filter { (_, total) -> total != 0 }
                            .map { (id, _) -> id }
                            .toSet()
                        if (validMainIds.isNotEmpty() && validMainIds.size < mainCategories.size) {
                            val filtered = categories.filter { it.pid != 0 || it.id in validMainIds }
                            cache.saveCategories(filtered)
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

                // 加载已有索引 + 后台全量构建
                cache.loadSearchIndexFromFile()
                cache.startBackgroundIndexIfNeeded()

            } catch (_: Exception) {
                _state.value = _state.value.copy(isInitialized = true)
            }
        }
    }

    fun startUpdate(context: Context) {
        viewModelScope.launch {
            val info = _state.value.updateAvailable ?: return@launch
            _state.value = _state.value.copy(
                isDownloading = true,
                loadingMessage = "正在下载更新...",
                downloadProgress = 0f
            )

            val apkFile = UpdateChecker.downloadApk(context, info.apkDownloadUrl) { progress ->
                _state.value = _state.value.copy(downloadProgress = progress)
            }

            if (apkFile != null) {
                _state.value = _state.value.copy(
                    isDownloading = false,
                    loadingMessage = "正在安装...",
                    downloadProgress = 1f
                )
                UpdateChecker.installApk(context, apkFile)
            } else {
                _state.value = _state.value.copy(
                    isDownloading = false,
                    loadingMessage = "下载失败，请重试"
                )
            }
        }
    }
}
