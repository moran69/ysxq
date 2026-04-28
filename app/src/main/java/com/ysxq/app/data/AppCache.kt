package com.ysxq.app.data

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 全局内存缓存，跨 ViewModel 持久化，避免重复网络请求
 */
object AppCache {
    private val lock = Any()

    @Volatile private var appContext: Context? = null
    private val indexScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile var isIndexBuilding: Boolean = false
        private set

    // 分类列表缓存
    @Volatile
    var categories: List<VideoCategory> = emptyList()
        private set

    // 视频列表缓存: key = "${ac}_${typeId}_${page}_${area}_${year}_${keyword}"
    private val videoListCache = java.util.concurrent.ConcurrentHashMap<String, CachedResponse>()

    // 视频详情缓存: key = videoId
    private val videoDetailCache = java.util.concurrent.ConcurrentHashMap<Int, VideoItem>()

    // 首页各栏目缓存
    @Volatile var homeMovies: List<VideoItem> = emptyList()
        private set
    @Volatile var homeTvSeries: List<VideoItem> = emptyList()
        private set
    @Volatile var homeAnime: List<VideoItem> = emptyList()
        private set
    @Volatile var homeVariety: List<VideoItem> = emptyList()
        private set
    @Volatile var homeBanners: List<VideoItem> = emptyList()
        private set
    @Volatile var homeLoaded: Boolean = false
        private set

    // 分类页上次浏览状态
    @Volatile var categoryLastMainCategoryId: Int = 0
    @Volatile var categoryLastGenreId: Int = 0
    @Volatile var categoryLastGenreName: String = "全部"
    @Volatile var categoryLastArea: String = "全部"
    @Volatile var categoryLastYear: String = "全部"
    @Volatile var categoryLastAreaOptions: List<String> = listOf("全部")
    @Volatile var categoryLastYearOptions: List<String> = listOf("全部")
    @Volatile var categoryLastSubCategories: List<VideoCategory> = emptyList()
    @Volatile var categoryViewStateSaved: Boolean = false

    data class CachedResponse(val list: List<VideoItem>, val pagecount: Int, val total: Int)

    // ========== 搜索索引 ==========

    // 紧凑存储：id → [name, pic]
    private val searchNames = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val searchPics = java.util.concurrent.ConcurrentHashMap<Int, String>()

    val searchIndexSize: Int get() = searchNames.size

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun addToSearchIndex(videos: List<VideoItem>) {
        for (v in videos) {
            if (v.name.isNotBlank()) {
                searchNames.putIfAbsent(v.id, v.name)
                searchPics.putIfAbsent(v.id, v.pic)
            }
        }
    }

    fun searchLocal(query: String, limit: Int = 20): List<VideoItem> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        return searchNames.entries
            .filter { (_, name) -> name.lowercase().contains(q) }
            .take(limit)
            .map { (id, name) ->
                VideoItem(
                    id = id,
                    name = name,
                    pic = searchPics[id] ?: ""
                )
            }
    }

    /** 从本地文件加载索引（启动时调用，秒级） */
    fun loadSearchIndexFromFile() {
        val ctx = appContext ?: return
        val file = File(ctx.filesDir, "search_index.json")
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getInt("i")
                val name = obj.getString("n")
                val pic = obj.optString("p", "")
                if (name.isNotBlank()) {
                    searchNames.putIfAbsent(id, name)
                    searchPics.putIfAbsent(id, pic)
                }
            }
        } catch (_: Exception) { }
    }

    /** 保存索引到本地文件 */
    private fun saveSearchIndexToFile() {
        val ctx = appContext ?: return
        try {
            val arr = JSONArray()
            for ((id, name) in searchNames) {
                arr.put(JSONObject()
                    .put("i", id)
                    .put("n", name)
                    .put("p", searchPics[id] ?: "")
                )
            }
            File(ctx.filesDir, "search_index.json").writeText(arr.toString())
            saveIndexMeta()
        } catch (_: Exception) { }
    }

    private fun saveIndexMeta() {
        val ctx = appContext ?: return
        try {
            File(ctx.filesDir, "search_index_meta.json").writeText(
                JSONObject().put("ts", System.currentTimeMillis()).toString()
            )
        } catch (_: Exception) { }
    }

    private fun lastIndexTime(): Long {
        val ctx = appContext ?: return 0
        try {
            val file = File(ctx.filesDir, "search_index_meta.json")
            if (!file.exists()) return 0
            return JSONObject(file.readText()).optLong("ts", 0)
        } catch (_: Exception) { return 0 }
    }

    /** 启动后台索引：无索引则全量构建，有索引则增量更新（仅抓新数据） */
    fun startBackgroundIndexIfNeeded() {
        val ctx = appContext ?: return
        if (isIndexBuilding) return
        isIndexBuilding = true
        val api = NetworkModule.apiService
        val lastTs = lastIndexTime()
        val needFullBuild = searchNames.isEmpty() || lastTs == 0L
        val needIncremental = !needFullBuild && (System.currentTimeMillis() - lastTs > 12 * 3600_000L)

        if (!needFullBuild && !needIncremental) {
            isIndexBuilding = false
            return
        }

        indexScope.launch {
            try {
                if (needFullBuild) {
                    fullBuild(api)
                } else {
                    incrementalUpdate(api)
                }
            } catch (_: Exception) { }
            finally { isIndexBuilding = false }
        }
    }

    /** 全量构建：从头抓取所有页 */
    private suspend fun fullBuild(api: com.ysxq.app.data.ApiService) {
        val first = api.getVideoList(ac = "detail", page = 1)
        val totalPages = first.pagecount
        addToSearchIndex(first.list)

        val semaphore = Semaphore(8)
        var page = 2
        while (page <= totalPages && currentCoroutineContext().isActive) {
            var batchIdx = 0
            coroutineScope {
                while (batchIdx < 8 && page <= totalPages) {
                    val p = page++
                    batchIdx++
                    launch {
                        semaphore.acquire()
                        try {
                            val resp = api.getVideoList(ac = "detail", page = p)
                            addToSearchIndex(resp.list)
                        } catch (_: Exception) { }
                        finally { semaphore.release() }
                    }
                }
            }
            delay(80)
            if (page % 200 == 0) saveSearchIndexToFile()
        }
        saveSearchIndexToFile()
    }

    /** 增量更新：从第1页开始抓，直到遇到已存在的ID为止 */
    private suspend fun incrementalUpdate(api: com.ysxq.app.data.ApiService) {
        var page = 1
        var hasNew = false
        while (currentCoroutineContext().isActive) {
            try {
                val resp = api.getVideoList(ac = "detail", page = page)
                if (resp.list.isEmpty()) break

                var newCount = 0
                for (v in resp.list) {
                    if (v.name.isNotBlank() && searchNames.putIfAbsent(v.id, v.name) == null) {
                        searchPics.putIfAbsent(v.id, v.pic)
                        newCount++
                    }
                }

                if (newCount == 0 && page > 1) break
                hasNew = hasNew || newCount > 0
                if (page >= resp.pagecount) break
                page++
                delay(100)
            } catch (_: Exception) { break }
        }
        if (hasNew) saveSearchIndexToFile()
        else saveIndexMeta()
    }

    // ========== 通用缓存方法 ==========

    fun saveCategories(cats: List<VideoCategory>) {
        synchronized(lock) {
            categories = cats
        }
    }

    fun saveVideoList(key: String, resp: CachedResponse) {
        videoListCache[key] = resp
    }

    fun getVideoList(key: String): CachedResponse? = videoListCache[key]

    fun saveVideoDetail(id: Int, video: VideoItem) {
        videoDetailCache[id] = video
    }

    fun getVideoDetail(id: Int): VideoItem? = videoDetailCache[id]

    fun saveHomeData(
        movies: List<VideoItem>,
        tvSeries: List<VideoItem>,
        anime: List<VideoItem>,
        variety: List<VideoItem>,
        banners: List<VideoItem>
    ) {
        synchronized(lock) {
            homeMovies = movies
            homeTvSeries = tvSeries
            homeAnime = anime
            homeVariety = variety
            homeBanners = banners
            homeLoaded = true
        }
    }

    fun saveCategoryViewState(
        mainCategoryId: Int,
        genreId: Int,
        genreName: String,
        area: String,
        year: String,
        areaOptions: List<String>,
        yearOptions: List<String>,
        subCategories: List<VideoCategory>
    ) {
        synchronized(lock) {
            categoryLastMainCategoryId = mainCategoryId
            categoryLastGenreId = genreId
            categoryLastGenreName = genreName
            categoryLastArea = area
            categoryLastYear = year
            categoryLastAreaOptions = areaOptions
            categoryLastYearOptions = yearOptions
            categoryLastSubCategories = subCategories
            categoryViewStateSaved = true
        }
    }

    fun videoListKey(ac: String, typeId: Int?, page: Int, area: String?, year: String?, keyword: String?): String {
        return "${ac}_${typeId ?: 0}_${page}_${area ?: ""}_${year ?: ""}_${keyword ?: ""}"
    }
}
