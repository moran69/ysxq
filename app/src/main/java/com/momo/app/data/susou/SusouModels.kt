package com.momo.app.data.susou

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 速搜视频 (com.sjz.ss) 片源协议数据模型
 * 来源: 逆向破解报告 (见 momo_susou_integration/速搜破解报告与momo集成方案.md)
 */

// ===== 速搜自家 api.php/app 接口 =====

@Serializable
data class SusouSearchResponse(
    val code: Int = 0,
    val msg: String = "",
    val list: List<SusouVideoItem> = emptyList(),
    // 注意: search 返回的 debug 是对象(如 {"result_limit":30,...}), 不能用 String
    val debug: JsonElement? = null
)

@Serializable
data class SusouVideoItem(
    @SerialName("vod_id") val vodId: Int = 0,
    @SerialName("vod_name") val vodName: String = "",
    @SerialName("vod_pic") val vodPic: String = "",
    @SerialName("type_id") val typeId: Int = 0,
    @SerialName("vod_remarks") val vodRemarks: String = "",
    @SerialName("vod_area") val vodArea: String = "",
    @SerialName("vod_year") val vodYear: String = "",
    @SerialName("vod_actor") val vodActor: String = "",
    @SerialName("vod_director") val vodDirector: String = "",
    @SerialName("vod_score") val vodScore: String = "",
    // 注意: relevance_score 可能是小数(如 28.33), 不能用 Int
    @SerialName("relevance_score") val relevanceScore: Double = 0.0
)

@Serializable
data class SusouNavResponse(
    val code: Int = 0,
    val msg: String = "",
    val list: List<SusouCategory> = emptyList()
)

@Serializable
data class SusouCategory(
    @SerialName("type_id") val typeId: Int = 0,
    @SerialName("type_name") val typeName: String = "",
    @SerialName("type_extend") val typeExtend: SusouTypeExtend? = null
)

@Serializable
data class SusouTypeExtend(
    val `class`: String = "",
    val area: String = "",
    val lang: String = ""
)

// ===== 备用源 v.rbotv.cn (MD5 签名) =====

@Serializable
data class RbotvSearchResponse(
    val code: Int = 0,
    val msg: String = "",
    val data: RbotvSearchData? = null
)

@Serializable
data class RbotvSearchData(
    val list: List<RbotvVideoItem> = emptyList()
)

@Serializable
data class RbotvVideoItem(
    @SerialName("vod_id") val vodId: Int = 0,
    @SerialName("vod_name") val vodName: String = "",
    @SerialName("vod_pic") val vodPic: String = "",
    @SerialName("vod_remarks") val vodRemarks: String = "",
    @SerialName("vod_actor") val vodActor: String = "",
    @SerialName("vod_content") val vodContent: String = "",
    @SerialName("vod_score") val vodScore: String = "",
    @SerialName("vod_year") val vodYear: String = ""
)

@Serializable
data class RbotvDetailResponse(
    val code: Int = 0,
    val msg: String = "",
    val data: RbotvDetailData? = null
)

@Serializable
data class RbotvDetailData(
    @SerialName("vod_id") val vodId: Int = 0,
    @SerialName("vod_name") val vodName: String = "",
    @SerialName("vod_pic") val vodPic: String = "",
    @SerialName("vod_remarks") val vodRemarks: String = "",
    @SerialName("vod_actor") val vodActor: String = "",
    @SerialName("vod_director") val vodDirector: String = "",
    @SerialName("vod_content") val vodContent: String = "",
    @SerialName("vod_score") val vodScore: String = "",
    @SerialName("vod_area") val vodArea: String = "",
    @SerialName("vod_lang") val vodLang: String = "",
    @SerialName("vod_year") val vodYear: String = "",
    @SerialName("vod_play_list") val vodPlayList: List<RbotvPlayList> = emptyList()
)

@Serializable
data class RbotvPlayList(
    val name: String = "",
    val urls: List<RbotvEpisode> = emptyList(),
    // NBY 加密线路的解密接口前缀列表(如 https://api.nbyjson.top:7788/api/?key=xxx&url=)
    @SerialName("parse_urls") val parseUrls: List<String> = emptyList(),
    // 线路类型标识: nby=加密 / lz / ff=非凡 / bf=风暴 等, 用于映射友好名称
    val flag: String = ""
)

/** 备用源线路 flag → 友好显示名 (替代"线路二/三/四"式命名) */
fun friendlySourceName(flag: String, fallback: String): String = when (flag.lowercase()) {
    "nby" -> "备用源"
    "lz" -> "高清源"
    "ff" -> "非凡源"
    "bf" -> "风暴源"
    else -> fallback
}

@Serializable
data class RbotvEpisode(
    val name: String = "",
    val url: String = "",
    val nid: Int = 0
)

// ===== NBY 解密接口响应 (api.nbyjson.top) =====

@Serializable
data class NbyResolveResponse(
    val code: Int = 0,
    val url: String = "",
    val type: String = ""
)
