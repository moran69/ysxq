package com.momo.app.data.danmaku

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 真实弹幕数据源 — dmku.hls.one 公益弹幕库
 *
 * 链路（PC 端已全链路验证，赘婿第11集 2091 条弹幕）:
 * 1. 爱奇艺搜索（公开免鉴权）: search.video.iqiyi.com/o?if=html5&key=剧名
 *    → docinfos[].albumDocInfo: albumTitle / albumLink / itemTotalNumber
 * 2. 专辑页 SSR 反查全集: GET albumLink → 正则提取 "shortTitle":"X第N集" + "playUrl":".../v_xxx.html"
 *    → 集数→单集URL 映射（含搜索接口缺失的中间集，如赘婿第11集）
 * 3. dmku 拉弹幕: dmku.hls.one/?ac=dm&url=<单集URL>
 *    → {code:23, danum:N, danmuku:[[time,"right","#fff","32px",text],...]}
 *    time 单位: 秒（浮点）
 *
 * 发送弹幕: POST dmku.hls.one/?ac=dm  body={"player":substr(md5(url),8,16),"time":..,"text":"..","color":"#FFF","size":"24px"}
 */
object DmkuApi {

    private const val IQIYI_SEARCH = "https://search.video.iqiyi.com/o"
    private const val DMKU_BASE = "https://dmku.hls.one/"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ===== 缓存 =====
    /** 剧名 → (专辑信息, 集数→单集URL) */
    private val albumCache = ConcurrentHashMap<String, Pair<IqiyiAlbum, Map<Int, String>>>()
    /** "剧名#集数" → 弹幕列表 */
    private val danmakuCache = ConcurrentHashMap<String, List<DanmakuEntry>>()

    // ===== 模型 =====
    data class IqiyiAlbum(
        val title: String,
        val albumLink: String,
        val total: Int
    )

    /** 弹幕条目（time 已转毫秒） */
    data class DanmakuEntry(
        val text: String,
        val timeMs: Long,
        val color: Long,       // RGB
        val type: Int          // 1=滚动 4=底部 5=顶部
    )

    // ===== 工具 =====
    private fun md5(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun parseColor(raw: String): Long {
        var hex = raw.trim().removePrefix("#")
        if (hex.length == 3) {
            hex = hex.map { "$it$it" }.joinToString("")
        }
        return hex.toLongOrNull(16) ?: 0xFFFFFFL
    }

    private fun getString(obj: JsonObject, key: String): String =
        obj[key]?.jsonPrimitive?.contentOrNull ?: ""

    // ========== 1. 爱奇艺搜索 ==========
    private suspend fun searchIqiyi(keyword: String): IqiyiAlbum? =
        withContext(Dispatchers.IO) {
            try {
                val url = "$IQIYI_SEARCH?if=html5&key=${java.net.URLEncoder.encode(keyword, "UTF-8")}"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://www.iqiyi.com/")
                    .get()
                    .build()
                val body = client.newCall(request).execute().use { it.body?.string() ?: "" }
                val root = json.parseToJsonElement(body).jsonObject
                val docs = root["data"]?.jsonObject?.get("docinfos") as? JsonArray ?: return@withContext null
                for (doc in docs) {
                    val info = (doc as? JsonObject)?.get("albumDocInfo") as? JsonObject ?: continue
                    val title = getString(info, "albumTitle")
                    val link = getString(info, "albumLink")
                    val total = info["itemTotalNumber"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    if (title.isNotEmpty() && link.isNotEmpty()) {
                        return@withContext IqiyiAlbum(title, link, total)
                    }
                }
                null
            } catch (e: Exception) {
                null
            }
        }

    // ========== 2. 专辑页 SSR 反查全集 ==========
    private suspend fun fetchAlbumEpisodes(albumLink: String): Map<Int, String> =
        withContext(Dispatchers.IO) {
            val eps = HashMap<Int, String>()
            try {
                // 电影/单集: albumLink 直接是 v_xxx.html 单集 URL
                if (albumLink.contains("/v_")) {
                    eps[1] = albumLink
                    return@withContext eps
                }
                val request = Request.Builder()
                    .url(albumLink)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Referer", "https://www.iqiyi.com/")
                    .get()
                    .build()
                val body = client.newCall(request).execute().use { it.body?.string() ?: "" }
                // 正则: "shortTitle":"X第N集"..."playUrl":"http://www.iqiyi.com/v_xxx.html"
                val pattern = Pattern.compile("\"shortTitle\":\"([^\"]*?)\".*?\"playUrl\":\"(http[^\"]*?v_[a-z0-9]+\\.html)\"")
                val m = pattern.matcher(body)
                while (m.find()) {
                    val title = m.group(1)
                    val url = m.group(2)
                    val numMatch = Regex("第(\\d+)集").find(title)
                    val num = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    eps[num] = url
                }
            } catch (e: Exception) {
                // 返回已解析的部分
            }
            eps
        }

    // ========== 3. dmku 拉弹幕 ==========
    private suspend fun fetchDmku(episodeUrl: String): List<DanmakuEntry> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$DMKU_BASE?ac=dm&url=${java.net.URLEncoder.encode(episodeUrl, "UTF-8")}"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "okhttp-okgo/jeasonlzy")
                    .get()
                    .build()
                val body = client.newCall(request).execute().use { it.body?.string() ?: "" }
                val root = json.parseToJsonElement(body).jsonObject
                val code = root["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                if (code != 23) return@withContext emptyList()
                val danmuku = root["danmuku"] as? JsonArray ?: return@withContext emptyList()
                val result = ArrayList<DanmakuEntry>(danmuku.size)
                for (item in danmuku) {
                    val arr = item as? JsonArray ?: continue
                    if (arr.size < 5) continue
                    val timeSec = arr[0].jsonPrimitive.contentOrNull?.toDoubleOrNull() ?: continue
                    val direction = arr[1].jsonPrimitive.contentOrNull ?: "right"
                    val colorStr = arr[2].jsonPrimitive.contentOrNull ?: "#fff"
                    val text = arr[4].jsonPrimitive.contentOrNull ?: continue
                    val type = when (direction) {
                        "top" -> 5
                        "bottom" -> 4
                        else -> 1
                    }
                    result.add(
                        DanmakuEntry(
                            text = text,
                            timeMs = (timeSec * 1000).toLong(),
                            color = parseColor(colorStr),
                            type = type
                        )
                    )
                }
                result
            } catch (e: Exception) {
                emptyList()
            }
        }

    // ========== 对外: 按剧名+集数取弹幕 ==========
    /**
     * @param videoName 剧名（如"赘婿"）
     * @param episodeNo 集数（从 1 开始；电影传 1）
     * @param useCache  命中缓存直接返回
     */
    suspend fun resolveDanmaku(videoName: String, episodeNo: Int, useCache: Boolean = true): List<DanmakuEntry> {
        if (videoName.isBlank()) return emptyList()
        val cacheKey = "$videoName#$episodeNo"
        if (useCache) {
            danmakuCache[cacheKey]?.let { return it }
        }

        // 1. 专辑信息 + 全集 URL（进程内缓存）
        var cached = albumCache[videoName]
        if (cached == null) {
            val info = searchIqiyi(videoName) ?: return emptyList()
            val eps = fetchAlbumEpisodes(info.albumLink)
            if (eps.isEmpty()) return emptyList()
            cached = info to eps
            albumCache[videoName] = cached
        }
        val album = cached.first
        val episodeUrls = cached.second
        val episodeUrl = episodeUrls[episodeNo] ?: episodeUrls.values.firstOrNull() ?: return emptyList()

        // 3. dmku 拉弹幕
        val danmaku = fetchDmku(episodeUrl)
        if (danmaku.isNotEmpty()) {
            danmakuCache[cacheKey] = danmaku
        }
        return danmaku
    }

    // ========== 对外: 发送弹幕 ==========
    /**
     * @param videoName 剧名
     * @param episodeNo 集数
     * @param positionMs 当前播放位置（毫秒）
     * @param text 弹幕内容
     * @param color 颜色（如 "#FFFFFF"）
     */
    suspend fun sendDanmaku(videoName: String, episodeNo: Int, positionMs: Long, text: String, color: String = "#FFFFFF"): Boolean =
        withContext(Dispatchers.IO) {
            try {
                // 需要先拿到该集的 URL
                val album = albumCache[videoName]?.first ?: searchIqiyi(videoName) ?: return@withContext false
                val episodeUrls = albumCache[videoName]?.second ?: fetchAlbumEpisodes(album.albumLink)
                val episodeUrl = episodeUrls[episodeNo] ?: return@withContext false

                val player = md5(episodeUrl).substring(8, 24) // substr(md5($url),8,16)
                val payload = buildJsonObject {
                    put("player", player)
                    put("time", positionMs / 1000.0)
                    put("text", text)
                    put("color", color)
                    put("size", "24px")
                }.toString()
                val request = Request.Builder()
                    .url(DMKU_BASE)
                    .header("User-Agent", "okhttp-okgo/jeasonlzy")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                val body = client.newCall(request).execute().use { it.body?.string() ?: "" }
                val root = json.parseToJsonElement(body).jsonObject
                (root["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0) == 23
            } catch (e: Exception) {
                false
            }
        }

    /** 清空缓存（切剧/切换线路时调用） */
    fun clearCache() {
        albumCache.clear()
        danmakuCache.clear()
    }
}
