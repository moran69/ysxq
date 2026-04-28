package com.ysxq.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse(
    val code: Int = 0,
    val msg: String = "",
    val page: Int = 1,
    val pagecount: Int = 0,
    val limit: String = "20",
    val total: Int = 0,
    val list: List<VideoItem> = emptyList(),
    val `class`: List<VideoCategory> = emptyList()
)

@Serializable
data class VideoItem(
    @SerialName("vod_id") val id: Int = 0,
    @SerialName("vod_name") val name: String = "",
    @SerialName("type_id") val typeId: Int = 0,
    @SerialName("type_id_1") val parentTypeId: Int = 0,
    @SerialName("type_name") val typeName: String = "",
    @SerialName("vod_en") val nameEn: String = "",
    @SerialName("vod_time") val time: String = "",
    @SerialName("vod_remarks") val remarks: String = "",
    @SerialName("vod_play_from") val playFrom: String = "",
    @SerialName("vod_pic") val pic: String = "",
    @SerialName("vod_actor") val actor: String = "",
    @SerialName("vod_director") val director: String = "",
    @SerialName("vod_content") val content: String = "",
    @SerialName("vod_area") val area: String = "",
    @SerialName("vod_lang") val lang: String = "",
    @SerialName("vod_year") val year: String = "",
    @SerialName("vod_play_url") val playUrl: String = "",
    @SerialName("vod_score") val score: String = "",
    @SerialName("vod_blurb") val blurb: String = "",
    @SerialName("vod_class") val vodClass: String = "",
    @SerialName("vod_tag") val tag: String = ""
)

@Serializable
data class VideoCategory(
    @SerialName("type_id") val id: Int = 0,
    @SerialName("type_pid") val pid: Int = 0,
    @SerialName("type_name") val name: String = ""
)

data class VideoSource(
    val label: String,
    val episodes: List<Episode>
)

data class Episode(
    val name: String,
    val url: String
)

fun VideoItem.parsePlaySources(): List<VideoSource> {
    if (playFrom.isBlank() || playUrl.isBlank()) return emptyList()

    // Some APIs use $$$ as separator for playFrom instead of comma
    val sourceNames = if (playFrom.contains(",")) {
        playFrom.split(",").filter { it.isNotBlank() }
    } else if (playFrom.contains("$$$")) {
        playFrom.split("$$$").filter { it.isNotBlank() }
    } else {
        listOf(playFrom.trim())
    }
    val sourceUrls = playUrl.split("$$$").filter { it.isNotBlank() }

    return sourceNames.mapIndexed { index, sourceName ->
        val episodesStr = sourceUrls.getOrElse(index) { "" }
        val episodes = episodesStr.split("#")
            .filter { it.isNotBlank() }
            .mapNotNull { ep ->
                val parts = ep.split("$", limit = 2)
                if (parts.size >= 2) {
                    Episode(name = parts[0], url = parts[1])
                } else null
            }
        VideoSource(label = sourceName.trim(), episodes = episodes)
    }
}
