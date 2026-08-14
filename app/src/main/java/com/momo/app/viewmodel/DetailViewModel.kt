package com.momo.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.momo.app.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DetailState(
    val isLoading: Boolean = false,
    val video: VideoItem? = null,
    val sources: List<VideoSource> = emptyList(),
    val currentSourceIndex: Int = 0,
    val currentEpisodeIndex: Int = 0,
    val error: String? = null
)

class DetailViewModel : ViewModel() {
    private val api = com.momo.app.data.NetworkModule.apiService
    private val cache = AppCache

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    private var lastLoadedId: Int = -1

    fun loadDetail(videoId: Int) {
        if (lastLoadedId == videoId && _state.value.video != null) return
        lastLoadedId = videoId

        // 先查全局缓存
        val cached = cache.getVideoDetail(videoId)
        if (cached != null) {
            applyVideo(cached)
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val resp = api.getVideoDetail(ac = "detail", id = videoId)
                val video = resp.list.firstOrNull()
                if (video != null) cache.saveVideoDetail(videoId, video)
                if (video != null) applyVideo(video) else {
                    _state.value = _state.value.copy(isLoading = false, error = "未找到影片信息")
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "加载失败")
            }
        }
    }

    private fun applyVideo(video: VideoItem) {
        val sources = video.parsePlaySources().filter { it.label != "liangzi" }
        val preferredIndex = sources.indexOfFirst { it.label.contains("m3u8", ignoreCase = true) }.takeIf { it >= 0 } ?: 0
        _state.value = _state.value.copy(
            isLoading = false,
            video = video,
            sources = sources,
            currentSourceIndex = preferredIndex,
            currentEpisodeIndex = if (sources.getOrNull(preferredIndex)?.episodes?.isNotEmpty() == true) 0 else -1,
            error = null
        )
    }

    /**
     * 外部片源注入（速搜片源等）：直接填充 state，不走自家网络请求。
     * 复用现有播放/投屏/下载逻辑。
     * 保留全部线路（含 NBY 加密线路，播放时懒解密），优先直链 m3u8 线路。
     */
    fun injectExternal(video: VideoItem, sources: List<VideoSource>) {
        // 仅保留含可播放 ep 的线路(空线路丢弃; NBY 线路保留, 由 resolvePlayUrl 懒解密)
        val filtered = sources.mapNotNull { src ->
            val eps = src.episodes.filter { ep ->
                val url = ep.url.trim()
                url.startsWith("http") || url.startsWith("NBY-")
            }
            if (eps.isEmpty()) null else VideoSource(label = src.label, episodes = eps)
        }
        // 优先选第一集就是 m3u8 直链的线路(直链稳定, NBY 解密有失效风险放后面)
        val preferredIndex = filtered.indexOfFirst { src ->
            src.episodes.firstOrNull()?.url?.contains("m3u8", ignoreCase = true) == true
        }.takeIf { it >= 0 } ?: 0
        _state.value = DetailState(
            isLoading = false,
            video = video,
            sources = filtered,
            currentSourceIndex = if (filtered.getOrNull(preferredIndex)?.episodes?.isNotEmpty() == true) preferredIndex else 0,
            currentEpisodeIndex = if (filtered.getOrNull(preferredIndex)?.episodes?.isNotEmpty() == true) 0 else -1,
            error = null
        )
    }

    fun selectSource(index: Int) {
        _state.value = _state.value.copy(currentSourceIndex = index, currentEpisodeIndex = 0)
    }

    fun selectEpisode(index: Int) {
        _state.value = _state.value.copy(currentEpisodeIndex = index)
    }

    fun getCurrentEpisodeUrl(): String? {
        val s = _state.value
        return s.sources.getOrNull(s.currentSourceIndex)?.episodes?.getOrNull(s.currentEpisodeIndex)?.url
    }

    /**
     * 懒解密：当前剧集 URL 若为 NBY 加密串，调用备用源 parse_urls 接口解出真实 m3u8 地址。
     * 播放/投屏前调用。解密失败返回原始串（由上层 Toast 提示换线路）。
     */
    suspend fun resolvePlayUrl(raw: String): String {
        val s = _state.value
        val ep = s.sources.getOrNull(s.currentSourceIndex)
            ?.episodes?.getOrNull(s.currentEpisodeIndex)
        val url = ep?.url?.trim().orEmpty()
        // 看剧AI token 懒解析 (YJ-xxx → m3u8 直链)
        if (url.startsWith("YJ-")) {
            val resolved = com.momo.app.data.kanjuai.KanjuAiApi.resolvePlayback(url)
            android.util.Log.d("KanjuAiVM", "resolvePlayUrl: 看剧AI解析 ${if (resolved != null) "成功" else "失败"} (${url.take(30)}...)")
            return resolved?.url ?: raw
        }
        // 非 NBY 或 URL 已变化(切集竞态) → 直接用传入值
        if (!url.startsWith("NBY-")) return raw
        val resolved = com.momo.app.data.susou.SusouApi.resolveNby(url, ep?.parseUrls ?: emptyList())
        android.util.Log.d("SusouVM", "resolvePlayUrl: NBY 解密 ${if (resolved != null) "成功" else "失败"} (${url.take(30)}...)")
        return resolved ?: raw
    }
}
