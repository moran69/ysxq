package com.momo.app.data.kanjuai

import com.momo.app.data.Episode
import com.momo.app.data.VideoItem
import com.momo.app.data.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 看剧AI (kanju1.com) 片源接口封装 — 全链路已验证
 *
 * API 架构: REST + HMAC-SHA256 请求签名 + cookie session
 *
 * 片源链路:
 * 1. 匿名登录  → POST /v1/users/anonymous → 获取 session cookie
 * 2. 搜索      → GET /v1/suggest?q=xxx → 返回 variant_id 列表
 * 3. 详情      → GET /v1/catalog/{variantId}/detail → 返回标题/演员/简介等
 * 4. 剧集列表   → GET /v1/catalog/{variantId}/episodes → 返回 episode token 列表
 * 5. 播放解析   → GET https://player.baipiaozhe.com/v1/playback/resolve/{token} → 返回 m3u8 直链
 * 6. 播放       → m3u8 直链可直接播放(无需 Referer/cookie)
 */
object KanjuAiApi {

    // ===== 常量 =====
    private const val BASE_URL = "https://kanju1.com"
    private const val RESOLVE_BASE = "https://player.baipiaozhe.com/v1/playback/resolve"
    private const val HMAC_SECRET = "557d0e4ae929f438da6bd84412374e6086b8af09b3fed54bf22601d5bf8c54a0"
    private const val CLIENT_NAME = "aimovie-web"
    private const val CLIENT_VERSION = "0.1.9"
    private const val ANONYMOUS_ID = "momo_app_android_001"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    // ===== Cookie 管理 (内存) =====
    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(cookieJar)
        .build()

    // ===== HMAC-SHA256 签名 =====
    private fun signRequest(method: String, pathname: String, search: String): Triple<String, String, String> {
        val timestamp = System.currentTimeMillis().toString()
        val nonce = generateNonce()
        val message = "$method\n$pathname$search\n$timestamp\n$nonce"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return Triple(timestamp, nonce, signature)
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ===== 请求执行 =====
    private fun execSignedRequest(
        method: String,
        path: String,
        search: String = "",
        body: String? = null
    ): String {
        val (timestamp, nonce, signature) = signRequest(method, path, search)

        val urlBuilder = StringBuilder(BASE_URL).append(path)
        if (search.isNotEmpty()) urlBuilder.append(search)

        val reqBuilder = Request.Builder()
            .url(urlBuilder.toString())
            .header("Accept", "application/json")
            .header("x-ai-movie-client-name", CLIENT_NAME)
            .header("x-ai-movie-client-version", CLIENT_VERSION)
            .header("x-ai-movie-timestamp", timestamp)
            .header("x-ai-movie-nonce", nonce)
            .header("x-ai-movie-signature", signature)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")

        when (method) {
            "GET" -> reqBuilder.get()
            "POST" -> {
                reqBuilder.post((body ?: "{}").toRequestBody("application/json".toMediaType()))
            }
        }

        client.newCall(reqBuilder.build()).execute().use { resp ->
            val respBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code}: $respBody")
            }
            return respBody
        }
    }

    // ===== 1. 匿名登录 =====
    private var sessionInitialized = false

    private suspend fun ensureSession() {
        if (sessionInitialized) return
        withContext(Dispatchers.IO) {
            val body = """{"anonymous_id":"$ANONYMOUS_ID"}"""
            execSignedRequest("POST", "/v1/users/anonymous", body = body)
            sessionInitialized = true
        }
    }


    // ===== 0. 首页推荐 (/v1/feed/home) =====
    suspend fun homeFeed(): List<KanjuAiHomeSection> = withContext(Dispatchers.IO) {
        ensureSession()
        try {
            val search = "?scope=public&mode=preview&sections=3&cards=10&adult_confirmed=false"
            val respBody = execSignedRequest("GET", "/v1/feed/home", search)
            val resp = json.decodeFromString(KanjuAiHomeFeedResponse.serializer(), respBody)
            resp.sections.filter { it.cards.isNotEmpty() }
        } catch (e: Exception) {
            android.util.Log.e("KanjuAiApi", "homeFeed failed", e)
            emptyList()
        }
    }

    // ===== 0b. 热门榜单 (/v1/browse/catalog?sort=trending) =====
    suspend fun trending(window: String = "day", limit: Int = 20): List<KanjuAiTrendingCard> = withContext(Dispatchers.IO) {
        ensureSession()
        try {
            val search = "?sort=trending&window=$window&page=1&limit=$limit"
            val respBody = execSignedRequest("GET", "/v1/browse/catalog", search)
            val resp = json.decodeFromString(KanjuAiTrendingResponse.serializer(), respBody)
            resp.cards
        } catch (e: Exception) {
            android.util.Log.e("KanjuAiApi", "trending failed", e)
            emptyList()
        }
    }

    // ===== 2. 搜索 =====
    suspend fun search(keyword: String): List<KanjuAiSuggestion> = withContext(Dispatchers.IO) {
        ensureSession()
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
        val search = "?q=$encoded&limit=20"
        val respBody = execSignedRequest("GET", "/v1/suggest", search)
        val resp = json.decodeFromString(KanjuAiSuggestResponse.serializer(), respBody)
        resp.suggestions.filter { it.target?.variantId?.isNotBlank() == true }
    }

    // ===== 3. 详情 =====
    suspend fun getDetail(variantId: String): KanjuAiDetailResponse? = withContext(Dispatchers.IO) {
        ensureSession()
        try {
            val path = "/v1/catalog/$variantId/detail"
            val respBody = execSignedRequest("GET", path)
            json.decodeFromString(KanjuAiDetailResponse.serializer(), respBody)
        } catch (e: Exception) {
            android.util.Log.e("KanjuAiApi", "getDetail failed", e)
            null
        }
    }

    // ===== 4. 剧集列表 =====
    suspend fun getEpisodes(variantId: String): KanjuAiEpisodesResponse? = withContext(Dispatchers.IO) {
        ensureSession()
        try {
            val path = "/v1/catalog/$variantId/episodes"
            val search = "?limit=100&offset=0&order=start"
            val respBody = execSignedRequest("GET", path, search)
            json.decodeFromString(KanjuAiEpisodesResponse.serializer(), respBody)
        } catch (e: Exception) {
            android.util.Log.e("KanjuAiApi", "getEpisodes failed", e)
            null
        }
    }

    // ===== 5. 播放线路解析 (player.baipiaozhe.com, 无需签名) =====
    suspend fun resolvePlayback(token: String): KanjuAiResolveResponse? = withContext(Dispatchers.IO) {
        try {
            // token 格式: YJ-xxxxxxxxx, 需要去掉 .m3u8 后缀(如果有)
            val cleanToken = token.replace("\\.m3u8$".toRegex(), "")
            val url = "$RESOLVE_BASE/${java.net.URLEncoder.encode(cleanToken, "UTF-8")}"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    android.util.Log.e("KanjuAiApi", "resolvePlayback HTTP ${resp.code}: $body")
                    return@use null
                }
                json.decodeFromString(KanjuAiResolveResponse.serializer(), body)
            }
        } catch (e: Exception) {
            android.util.Log.e("KanjuAiApi", "resolvePlayback failed", e)
            null
        }
    }

    // ===== 6. 完整详情解析: 搜索 → 详情 → 剧集 → 解析播放 =====
    /**
     * 搜索 → 取第一条 → 获取详情+剧集 → 构造 VideoItem + VideoSource
     * 播放 URL 在用户选择集数时懒解析(resolvePlayback)
     */
    suspend fun fetchVideoDetail(keyword: String): Pair<VideoItem, List<VideoSource>>? {
        return withContext(Dispatchers.IO) {
            try {
                // 搜索
                val suggestions = search(keyword)
                val target = suggestions.firstOrNull { it.target?.directPlayable == true }
                    ?: suggestions.firstOrNull() ?: return@withContext null

                val variantId = target.target?.variantId ?: return@withContext null

                // 获取详情
                val detail = getDetail(variantId) ?: return@withContext null

                // 获取剧集
                val episodes = getEpisodes(variantId) ?: return@withContext null

                // 构造 VideoItem
                val videoId = Math.abs(variantId.hashCode())
                val video = VideoItem(
                    id = videoId,
                    name = detail.title.ifBlank { target.label },
                    pic = detail.posterUrl,
                    remarks = detail.remarks.ifBlank { "全${detail.availableEpisodeCount}集" },
                    actor = detail.actors.joinToString(", "),
                    director = detail.directors.joinToString(", "),
                    content = detail.description,
                    area = detail.area,
                    lang = detail.language,
                    year = detail.year.toString(),
                    score = ""
                )

                // 构造 VideoSource — 每个 episode 的 token 作为 url (播放时懒解析)
                val eps = episodes.episodes.map { ep ->
                    Episode(
                        name = ep.title.ifBlank { "第${ep.number}集" },
                        url = ep.token  // 存 token, 播放时通过 resolvePlayback 解析
                    )
                }

                if (eps.isEmpty()) return@withContext null

                val sources = listOf(VideoSource(label = "看剧AI", episodes = eps))
                video to sources
            } catch (e: Exception) {
                android.util.Log.e("KanjuAiApi", "fetchVideoDetail failed", e)
                null
            }
        }
    }

    /**
     * 按 variantId 获取详情+剧集 (从搜索结果点击进入)
     */
    suspend fun fetchVideoDetailByVariantId(
        variantId: String,
        fallbackTitle: String = ""
    ): Pair<VideoItem, List<VideoSource>>? {
        return withContext(Dispatchers.IO) {
            try {
                val detail = getDetail(variantId) ?: return@withContext null
                val episodes = getEpisodes(variantId) ?: return@withContext null

                val videoId = Math.abs(variantId.hashCode())
                val video = VideoItem(
                    id = videoId,
                    name = detail.title.ifBlank { fallbackTitle },
                    pic = detail.posterUrl,
                    remarks = detail.remarks.ifBlank { "全${detail.availableEpisodeCount}集" },
                    actor = detail.actors.joinToString(", "),
                    director = detail.directors.joinToString(", "),
                    content = detail.description,
                    area = detail.area,
                    lang = detail.language,
                    year = detail.year.toString(),
                    score = ""
                )

                val eps = episodes.episodes.map { ep ->
                    Episode(
                        name = ep.title.ifBlank { "第${ep.number}集" },
                        url = ep.token
                    )
                }

                if (eps.isEmpty()) return@withContext null

                val sources = listOf(VideoSource(label = "看剧AI", episodes = eps))
                video to sources
            } catch (e: Exception) {
                android.util.Log.e("KanjuAiApi", "fetchVideoDetailByVariantId failed", e)
                null
            }
        }
    }
}

/**
 * 看剧AI详情导航暂存：DetailScreen 路由参数只传 videoId，
 * 大对象(VideoItem + sources) 经此单例中转，进入详情页时一次性取走。
 */
object KanjuAiNavHolder {
    private var pending: Pair<VideoItem, List<VideoSource>>? = null

    fun put(data: Pair<VideoItem, List<VideoSource>>) {
        pending = data
    }

    fun take(videoId: Int): Pair<VideoItem, List<VideoSource>>? {
        val p = pending
        pending = null
        return p?.takeIf { it.first.id == videoId }
    }
}
