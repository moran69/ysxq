package com.momo.app.data.kanjuai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 看剧AI (kanju1.com) 片源协议数据模型
 *
 * API 架构: RTK Query + HMAC-SHA256 签名 + cookie session
 * 签名: HMAC(secret, "METHOD\npathname+search\ntimestamp\nnonce")
 * 密钥: 557d0e4ae929f438da6bd84412374e6086b8af09b3fed54bf22601d5bf8c54a0
 */

// ===== 搜索建议 =====

@Serializable
data class KanjuAiSuggestResponse(
    val `object`: String = "",
    val query: String = "",
    val suggestions: List<KanjuAiSuggestion> = emptyList()
)

@Serializable
data class KanjuAiSuggestion(
    val id: String = "",
    val type: String = "",
    val label: String = "",
    val subtitle: String = "",
    val prompt: String = "",
    val score: Double = 0.0,
    val target: KanjuAiSuggestionTarget? = null
)

@Serializable
data class KanjuAiSuggestionTarget(
    @SerialName("variant_id") val variantId: String = "",
    val title: String = "",
    @SerialName("direct_playable") val directPlayable: Boolean = false
)

// ===== 详情 =====

@Serializable
data class KanjuAiDetailResponse(
    val `object`: String = "",
    val id: String = "",
    @SerialName("variant_id") val variantId: String = "",
    val title: String = "",
    @SerialName("content_kind") val contentKind: String = "",
    val year: Int = 0,
    val area: String = "",
    val language: String = "",
    val genres: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    @SerialName("poster_url") val posterUrl: String = "",
    val remarks: String = "",
    val description: String = "",
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("available_episode_count") val availableEpisodeCount: Int = 0,
    val variants: List<KanjuAiVariant> = emptyList()
)

@Serializable
data class KanjuAiVariant(
    val id: String = "",
    @SerialName("variant_id") val variantId: String = "",
    val title: String = "",
    @SerialName("season_label") val seasonLabel: String = "",
    val year: Int = 0,
    @SerialName("available_episode_count") val availableEpisodeCount: Int = 0,
    @SerialName("has_playback") val hasPlayback: Boolean = false,
    val selected: Boolean = false
)

// ===== 剧集列表 =====

@Serializable
data class KanjuAiEpisodesResponse(
    val `object`: String = "",
    @SerialName("variant_id") val variantId: String = "",
    val title: String = "",
    @SerialName("content_kind") val contentKind: String = "",
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("available_episode_count") val availableEpisodeCount: Int = 0,
    @SerialName("episode_pagination") val episodePagination: KanjuAiPagination? = null,
    val episodes: List<KanjuAiEpisode> = emptyList()
)

@Serializable
data class KanjuAiPagination(
    val offset: Int = 0,
    val limit: Int = 0,
    @SerialName("returned_count") val returnedCount: Int = 0,
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("has_more") val hasMore: Boolean = false
)

@Serializable
data class KanjuAiEpisode(
    val id: String = "",
    val number: Int = 0,
    val key: String = "",
    val title: String = "",
    val token: String = "",
    val path: String = "",
    @SerialName("playback_count") val playbackCount: Int = 0,
    val urls: KanjuAiEpisodeUrls? = null
)

@Serializable
data class KanjuAiEpisodeUrls(
    val yjapi: String = "",
    val yjm3u8: String = "",
    val yjplayer: String = ""
)

// ===== 播放线路解析 (player.baipiaozhe.com) =====

@Serializable
data class KanjuAiResolveResponse(
    val `object`: String = "",
    val token: String = "",
    @SerialName("episode_id") val episodeId: String = "",
    @SerialName("playback_source_id") val playbackSourceId: String = "",
    @SerialName("provider_id") val providerId: String = "",
    @SerialName("play_from") val playFrom: String = "",
    val url: String = "",
    @SerialName("url_kind") val urlKind: String = "",
    val score: Int = 0,
    val candidates: Int = 0,
    @SerialName("content_kind") val contentKind: String = "",
    @SerialName("current_episode") val currentEpisode: KanjuAiResolveEpisode? = null,
    val episodes: List<KanjuAiResolveEpisode> = emptyList()
)

@Serializable
data class KanjuAiResolveEpisode(
    @SerialName("episode_id") val episodeId: String = "",
    val token: String = "",
    @SerialName("playback_source_id") val playbackSourceId: String = "",
    @SerialName("provider_id") val providerId: String = "",
    @SerialName("play_from") val playFrom: String = "",
    @SerialName("source_vod_id") val sourceVodId: String = "",
    @SerialName("episode_number") val episodeNumber: Int = 0,
    @SerialName("sequence_index") val sequenceIndex: Int = 0,
    @SerialName("previous_episode_token") val previousEpisodeToken: String? = null,
    @SerialName("next_episode_token") val nextEpisodeToken: String? = null,
    @SerialName("episode_key") val episodeKey: String = "",
    @SerialName("display_name") val displayName: String = "",
    val path: String = "",
    @SerialName("playback_count") val playbackCount: Int = 0,
    val selected: Boolean = false
)

// ===== 匿名登录 =====

@Serializable
data class KanjuAiAnonymousResponse(
    val `object`: String = "",
    val user: KanjuAiUser? = null,
    @SerialName("expires_at") val expiresAt: String = ""
)

@Serializable
data class KanjuAiUser(
    val id: String = "",
    @SerialName("display_name") val displayName: String = "",
    val status: String = ""
)
