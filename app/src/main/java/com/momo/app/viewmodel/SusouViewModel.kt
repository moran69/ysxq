package com.momo.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.momo.app.data.Episode
import com.momo.app.data.VideoItem
import com.momo.app.data.VideoSource
import com.momo.app.data.susou.SusouApi
import com.momo.app.data.susou.SusouVideoItem
import com.momo.app.data.susou.friendlySourceName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SusouState(
    val query: String = "",
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val results: List<SusouVideoItem> = emptyList(),
    val hotList: List<SusouVideoItem> = emptyList(),
    val error: String? = null,
    // 详情加载中指示（点击卡片后）
    val isDetailLoading: Boolean = false
)

class SusouViewModel : ViewModel() {

    private val _state = MutableStateFlow(SusouState())
    val state: StateFlow<SusouState> = _state.asStateFlow()

    init {
        loadHot()
    }

    /** 进入页面自动加载热门/周期表（AES 解密接口） */
    fun loadHot() {
        viewModelScope.launch {
            try {
                val list = SusouApi.weekday()
                _state.value = _state.value.copy(hotList = list)
            } catch (_: Exception) {
                // 周期表失败不影响使用，保留空态
            }
        }
    }

    fun updateQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun search() {
        val keyword = _state.value.query.trim()
        if (keyword.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, hasSearched = true, error = null)
            try {
                val list = SusouApi.search(keyword)
                _state.value = _state.value.copy(isLoading = false, results = list)
                if (list.isEmpty()) {
                    _state.value = _state.value.copy(error = "没有找到相关影片")
                }
            } catch (e: Exception) {
                android.util.Log.e("SusouVM", "搜索失败", e)
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "搜索失败"
                )
            }
        }
    }

    fun clearSearch() {
        _state.value = _state.value.copy(query = "", hasSearched = false, results = emptyList(), error = null)
    }

    /**
     * 点击速搜结果 → 备用源反查详情（ep 列表 m3u8 直链）
     * 返回 null 表示无可播放源
     */
    suspend fun resolveDetail(item: SusouVideoItem): Pair<VideoItem, List<VideoSource>>? {
        android.util.Log.d("SusouVM", "resolveDetail start: vodId=${item.vodId} name=${item.vodName}")
        return try {
            // 优先用速搜自家 vod_id 直接查备用源（同片源库，id 通常一致）
            val rbotv = try {
                SusouApi.rbotvDetail(item.vodId)
            } catch (e: Exception) {
                android.util.Log.e("SusouVM", "rbotvDetail(vodId=${item.vodId}) 失败: ${e.javaClass.simpleName}: ${e.message}", e)
                null
            }
            if (rbotv != null && rbotv.vodPlayList.isNotEmpty()) {
                android.util.Log.d("SusouVM", "rbotvDetail 命中: vodId=${rbotv.vodId} 线路数=${rbotv.vodPlayList.size}")
                buildDetail(item, rbotv.vodName, rbotv.vodPic, rbotv.vodRemarks, rbotv.vodPlayList)
            } else {
                android.util.Log.d("SusouVM", "rbotvDetail 未命中(vodPlayList=${rbotv?.vodPlayList?.size}), 走剧名反查: ${item.vodName}")
                SusouApi.fetchDetailByKeyword(item.vodName)
            }
        } catch (e: Exception) {
            android.util.Log.e("SusouVM", "resolveDetail 整体失败", e)
            null
        }
    }

    private fun buildDetail(
        item: SusouVideoItem,
        name: String,
        pic: String,
        remarks: String,
        playList: List<com.momo.app.data.susou.RbotvPlayList>
    ): Pair<VideoItem, List<VideoSource>>? {
        val sources = playList.mapNotNull { pl ->
            val parseUrls = pl.parseUrls
            val eps = pl.urls.mapNotNull { ep ->
                val url = ep.url.trim()
                when {
                    url.isBlank() -> null
                    // NBY 加密地址: 保留原串 + 附带解密接口前缀, 播放时懒解密
                    url.startsWith("NBY-") -> Episode(name = ep.name, url = url, parseUrls = parseUrls)
                    url.startsWith("http") -> Episode(name = ep.name, url = url)
                    else -> null
                }
            }
            if (eps.isEmpty()) null else VideoSource(label = friendlySourceName(pl.flag, pl.name), episodes = eps)
        }
        if (sources.isEmpty()) return null
        val video = VideoItem(
            id = item.vodId,
            name = name.ifBlank { item.vodName },
            pic = pic.ifBlank { item.vodPic },
            remarks = remarks.ifBlank { item.vodRemarks },
            typeName = "速搜",
            actor = item.vodActor,
            year = item.vodYear
        )
        return video to sources
    }
}
