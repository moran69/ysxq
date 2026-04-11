package com.ysxq.app.data

/**
 * 全局内存缓存，跨 ViewModel 持久化，避免重复网络请求
 */
object AppCache {
    // 分类列表缓存
    @Volatile
    var categories: List<VideoCategory> = emptyList()
        private set

    // 视频列表缓存: key = "${ac}_${typeId}_${page}_${area}_${year}_${keyword}"
    private val videoListCache = java.util.concurrent.ConcurrentHashMap<String, CachedResponse>()

    // 视频详情缓存: key = videoId
    private val videoDetailCache = java.util.concurrent.ConcurrentHashMap<Int, VideoItem>()

    // 首页各栏目缓存
    var homeMovies: List<VideoItem> = emptyList()
        private set
    var homeTvSeries: List<VideoItem> = emptyList()
        private set
    var homeAnime: List<VideoItem> = emptyList()
        private set
    var homeVariety: List<VideoItem> = emptyList()
        private set
    var homeBanners: List<VideoItem> = emptyList()
        private set
    var homeLoaded: Boolean = false
        private set

    // 分类页上次浏览状态
    var categoryLastMainCategoryId: Int = 0
    var categoryLastGenreId: Int = 0
    var categoryLastGenreName: String = "全部"
    var categoryLastArea: String = "全部"
    var categoryLastYear: String = "全部"
    var categoryLastAreaOptions: List<String> = listOf("全部")
    var categoryLastYearOptions: List<String> = listOf("全部")
    var categoryLastSubCategories: List<VideoCategory> = emptyList()
    var categoryViewStateSaved: Boolean = false

    data class CachedResponse(val list: List<VideoItem>, val pagecount: Int, val total: Int)

    fun saveCategories(cats: List<VideoCategory>) {
        categories = cats
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
        homeMovies = movies
        homeTvSeries = tvSeries
        homeAnime = anime
        homeVariety = variety
        homeBanners = banners
        homeLoaded = true
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

    fun videoListKey(ac: String, typeId: Int?, page: Int, area: String?, year: String?, keyword: String?): String {
        return "${ac}_${typeId ?: 0}_${page}_${area ?: ""}_${year ?: ""}_${keyword ?: ""}"
    }
}
