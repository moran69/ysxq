package com.ysxq.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ysxq.app.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CategoryState(
    val isLoading: Boolean = true,
    val videos: List<VideoItem> = emptyList(),
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val areaOptions: List<String> = listOf("全部"),
    val yearOptions: List<String> = listOf("全部"),
    val selectedArea: String = "全部",
    val selectedGenreId: Int = 0,
    val selectedGenreName: String = "全部",
    val selectedYear: String = "全部",
    val allCategories: List<VideoCategory> = emptyList(),
    val mainCategories: List<VideoCategory> = emptyList(),
    val selectedMainCategoryId: Int = 0,
    val subCategories: List<VideoCategory> = emptyList()
)

class CategoryViewModel : ViewModel() {
    private val api = com.ysxq.app.data.NetworkModule.apiService
    private val cache = AppCache

    private val _state = MutableStateFlow(CategoryState())
    val state: StateFlow<CategoryState> = _state.asStateFlow()

    private var categoriesLoaded = false
    private var loadMoreJob: Job? = null

    init {
        loadCategoriesAndFilters()
    }

    /** 从首页/外部跳转时设置初始分类（自动解析子分类到父级主分类） */
    fun setInitialCategory(categoryId: Int) {
        if (categoryId <= 0) return
        val s = _state.value

        // 解析：如果传的是子分类 ID，找到其父级主分类
        val allCats = s.allCategories.ifEmpty { cache.categories }
        val target = allCats.find { it.id == categoryId }
        val mainCatId = if (target != null && target.pid != 0) target.pid else categoryId

        if (s.selectedMainCategoryId == mainCatId) return
        if (s.mainCategories.isEmpty()) {
            _state.value = s.copy(selectedMainCategoryId = mainCatId)
            return
        }
        selectMainCategory(mainCatId)
    }

    private fun loadCategoriesAndFilters() {
        if (_state.value.videos.isNotEmpty() && _state.value.mainCategories.isNotEmpty()) {
            _state.value = _state.value.copy(isLoading = false)
            return
        }

        if (cache.categoryViewStateSaved && cache.categories.isNotEmpty()) {
            val allCategories = cache.categories
            val mainCategories = allCategories.filter { it.pid == 0 }
            val restoredMainId = cache.categoryLastMainCategoryId
            val subs = cache.categoryLastSubCategories.ifEmpty {
                allCategories.filter { it.pid == restoredMainId }
            }
            val typeId = if (cache.categoryLastGenreId > 0) cache.categoryLastGenreId
                else subs.firstOrNull()?.id ?: restoredMainId

            val key = cache.videoListKey("detail", typeId, 1, cache.categoryLastArea, cache.categoryLastYear, null)
            val cachedVideos = cache.getVideoList(key)

            _state.value = _state.value.copy(
                allCategories = allCategories,
                mainCategories = mainCategories,
                selectedMainCategoryId = restoredMainId,
                subCategories = subs,
                selectedGenreId = cache.categoryLastGenreId,
                selectedGenreName = cache.categoryLastGenreName,
                selectedArea = cache.categoryLastArea,
                selectedYear = cache.categoryLastYear,
                areaOptions = cache.categoryLastAreaOptions,
                yearOptions = cache.categoryLastYearOptions,
                isLoading = false,
                videos = cachedVideos?.list ?: emptyList(),
                totalPages = cachedVideos?.pagecount ?: 1,
                currentPage = 1
            )

            if (cachedVideos == null) {
                viewModelScope.launch {
                    loadFilterOptionsSync(restoredMainId)
                    loadVideos()
                }
            }
            return
        }

        if (cache.categories.isNotEmpty()) {
            val allCategories = cache.categories
            val mainCategories = allCategories.filter { it.pid == 0 }
            val initialId = _state.value.selectedMainCategoryId
            val defaultMainId = if (initialId > 0 && mainCategories.any { it.id == initialId }) initialId
                else mainCategories.firstOrNull()?.id ?: 1
            val subs = allCategories.filter { it.pid == defaultMainId }
            val typeId = subs.firstOrNull()?.id ?: defaultMainId

            val key = cache.videoListKey("detail", typeId, 1, "全部", "全部", null)
            val cachedVideos = cache.getVideoList(key)

            _state.value = _state.value.copy(
                allCategories = allCategories,
                mainCategories = mainCategories,
                selectedMainCategoryId = defaultMainId,
                subCategories = subs,
                isLoading = false,
                videos = cachedVideos?.list ?: emptyList(),
                totalPages = cachedVideos?.pagecount ?: 1,
                currentPage = 1
            )

            if (cachedVideos == null) {
                viewModelScope.launch {
                    loadFilterOptionsSync(defaultMainId)
                    loadVideos()
                }
            } else {
                viewModelScope.launch {
                    loadFilterOptionsFromCache(defaultMainId)
                    val state = _state.value
                    if (state.areaOptions.size <= 1 && state.yearOptions.size <= 1) {
                        loadFilterOptionsSync(defaultMainId)
                    }
                    saveViewState()
                }
            }
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                val resp = api.getVideoList(ac = "list")
                val allCategories = resp.`class`
                if (allCategories.isNotEmpty()) cache.saveCategories(allCategories)

                val mainCategories = allCategories.filter { it.pid == 0 }
                val initialId = _state.value.selectedMainCategoryId
                val defaultMainId = if (initialId > 0 && mainCategories.any { it.id == initialId }) initialId
                    else mainCategories.firstOrNull()?.id ?: 1
                val subs = allCategories.filter { it.pid == defaultMainId }
                val typeId = subs.firstOrNull()?.id ?: defaultMainId

                val key = cache.videoListKey("detail", typeId, 1, "全部", "全部", null)
                val cachedVideos = cache.getVideoList(key)

                if (cachedVideos != null) {
                    _state.value = _state.value.copy(
                        allCategories = allCategories,
                        mainCategories = mainCategories,
                        selectedMainCategoryId = defaultMainId,
                        subCategories = subs,
                        isLoading = false,
                        videos = cachedVideos.list,
                        totalPages = cachedVideos.pagecount,
                        currentPage = 1
                    )
                    loadFilterOptionsFromCache(defaultMainId)
                    if (_state.value.areaOptions.size <= 1 && _state.value.yearOptions.size <= 1) {
                        loadFilterOptionsSync(defaultMainId)
                    }
                    return@launch
                }

                _state.value = _state.value.copy(
                    allCategories = allCategories,
                    mainCategories = mainCategories,
                    selectedMainCategoryId = defaultMainId,
                    subCategories = subs
                )
                categoriesLoaded = true
                loadFilterOptionsSync(defaultMainId)
                loadVideos()
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun selectMainCategory(categoryId: Int) {
        val subs = _state.value.allCategories.filter { it.pid == categoryId }
        _state.value = _state.value.copy(
            selectedMainCategoryId = categoryId,
            subCategories = subs,
            selectedGenreId = 0,
            selectedGenreName = "全部",
            selectedArea = "全部",
            selectedYear = "全部",
            currentPage = 1,
            videos = emptyList(),
            isLoading = true
        )
        saveViewState()
        viewModelScope.launch {
            loadFilterOptionsFromCache(categoryId)
            if (_state.value.areaOptions.size <= 1 && _state.value.yearOptions.size <= 1) {
                loadFilterOptionsSync(categoryId)
            }
            loadVideos()
        }
    }

    fun selectGenre(genreId: Int, genreName: String) {
        _state.value = _state.value.copy(
            selectedGenreId = genreId,
            selectedGenreName = genreName,
            videos = emptyList(),
            currentPage = 1,
            isLoading = true
        )
        saveViewState()
        loadVideos()
    }

    fun selectArea(area: String) {
        _state.value = _state.value.copy(
            selectedArea = area,
            videos = emptyList(),
            currentPage = 1,
            isLoading = true
        )
        saveViewState()
        loadVideos()
    }

    fun selectYear(year: String) {
        _state.value = _state.value.copy(
            selectedYear = year,
            videos = emptyList(),
            currentPage = 1,
            isLoading = true
        )
        saveViewState()
        loadVideos()
    }

    private fun saveViewState() {
        val s = _state.value
        cache.saveCategoryViewState(
            mainCategoryId = s.selectedMainCategoryId,
            genreId = s.selectedGenreId,
            genreName = s.selectedGenreName,
            area = s.selectedArea,
            year = s.selectedYear,
            areaOptions = s.areaOptions,
            yearOptions = s.yearOptions,
            subCategories = s.subCategories
        )
    }

    fun loadMore() {
        val s = _state.value
        if (!s.isLoadingMore && s.currentPage < s.totalPages && loadMoreJob?.isActive != true) {
            loadVideos(page = s.currentPage + 1, isLoadMore = true)
        }
    }

    fun retry() {
        val s = _state.value
        if (s.videos.isEmpty()) {
            _state.value = s.copy(error = null, isLoading = true)
            loadVideos()
        } else {
            loadMore()
        }
    }

    private fun loadFilterOptionsFromCache(mainCategoryId: Int) {
        val subs = _state.value.allCategories.filter { it.pid == mainCategoryId }
        val typeIdsToQuery = if (subs.isNotEmpty()) subs.map { it.id } else listOf(mainCategoryId)

        val cachedAreas = mutableSetOf<String>()
        val cachedYears = mutableSetOf<String>()

        for (tid in typeIdsToQuery.take(5)) {
            for (page in 1..3) {
                val key = cache.videoListKey("detail", tid, page, null, null, null)
                val fallbackKey = cache.videoListKey("detail", tid, page, "全部", "全部", null)
                val cachedResp = cache.getVideoList(key) ?: cache.getVideoList(fallbackKey) ?: break
                cachedAreas.addAll(
                    cachedResp.list
                        .flatMap { it.area.split("/", "，", ",").map { a -> a.trim() } }
                        .filter { it.isNotBlank() }
                )
                cachedYears.addAll(
                    cachedResp.list
                        .map { it.year }
                        .filter { it.isNotBlank() }
                        .map { it.replace(Regex("[^0-9]"), "").trim() }
                        .filter { it.length == 4 && it.toIntOrNull() in 1900..2030 }
                )
                if (cachedResp.list.size < 20) break
            }
        }

        if (cachedAreas.isNotEmpty() || cachedYears.isNotEmpty()) {
            _state.value = _state.value.copy(
                areaOptions = listOf("全部") + cachedAreas.sorted(),
                yearOptions = listOf("全部") + cachedYears.sortedDescending()
            )
        }
    }

    private suspend fun loadFilterOptionsSync(mainCategoryId: Int) {
        val subs = _state.value.allCategories.filter { it.pid == mainCategoryId }
        val typeIdsToQuery = if (subs.isNotEmpty()) subs.map { it.id } else listOf(mainCategoryId)

        try {
            val allItems = mutableListOf<VideoItem>()
            for (tid in typeIdsToQuery.take(5)) {
                try {
                    val resp = api.getVideoList(ac = "detail", typeId = tid, page = 1)
                    allItems.addAll(resp.list)
                } catch (_: Exception) { }
            }

            val areas = allItems
                .flatMap { it.area.split("/", "，", ",").map { a -> a.trim() } }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

            val years = allItems
                .map { it.year }
                .filter { it.isNotBlank() }
                .map { it.replace(Regex("[^0-9]"), "").trim() }
                .filter { it.length == 4 && it.toIntOrNull() in 1900..2030 }
                .distinct()
                .sortedDescending()

            _state.value = _state.value.copy(
                areaOptions = listOf("全部") + areas,
                yearOptions = listOf("全部") + years
            )
        } catch (_: Exception) {}
    }

    private fun loadVideos(page: Int = 1, isLoadMore: Boolean = false) {
        val job = viewModelScope.launch {
            val s = _state.value
            val typeId = if (s.selectedGenreId > 0) s.selectedGenreId
                else s.subCategories.firstOrNull()?.id ?: s.selectedMainCategoryId

            val key = cache.videoListKey("detail", typeId, page, s.selectedArea, s.selectedYear, null)

            if (!isLoadMore) {
                val cached = cache.getVideoList(key)
                if (cached != null) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        currentPage = page,
                        totalPages = cached.pagecount,
                        videos = cached.list
                    )
                    saveViewState()
                    return@launch
                }
            }

            if (isLoadMore) {
                _state.value = _state.value.copy(isLoadingMore = true)
            } else {
                _state.value = _state.value.copy(isLoading = true, error = null)
            }

            try {
                val resp = api.getVideoList(
                    ac = "detail",
                    typeId = typeId,
                    page = page,
                    area = if (s.selectedArea == "全部") null else s.selectedArea,
                    year = if (s.selectedYear == "全部") null else s.selectedYear
                )

                val newVideos = if (isLoadMore) _state.value.videos + resp.list else resp.list

                cache.saveVideoList(key, AppCache.CachedResponse(newVideos, resp.pagecount, resp.total))
                cache.addToSearchIndex(resp.list)

                _state.value = _state.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    currentPage = page,
                    totalPages = resp.pagecount,
                    videos = newVideos
                )
                if (!isLoadMore) saveViewState()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    error = e.message ?: "加载失败"
                )
            }
        }
        if (isLoadMore) {
            loadMoreJob = job
        }
    }
}
