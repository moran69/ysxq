package com.momo.app.data.susou

import com.momo.app.data.Episode
import com.momo.app.data.VideoItem
import com.momo.app.data.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 速搜视频 (com.sjz.ss) 片源接口封装 — 全部链路已在 PC 端验证 (见 susou_api.py)
 *
 * 密钥与端口来自逆向破解（个人自用，不公开）:
 * - 业务接口 AES key: asdfghjklmnbvcxz (ECB/Pkcs7, 解密 weekday/nav 等)
 * - 备用源签名 key:    7gp0bnd2sr85ydii2j32pcypscoc4w6c7g5spl (MD5(key+timestamp))
 *
 * 片源链路:
 * 1. 搜索/列表  → 速搜自家  http://43.248.128.251:2233/api.php/app/search?text=xx (明文)
 * 2. 详情(ep)   → 备用源    http://v.rbotv.cn/v3/home/vod_details (MD5 签名, m3u8 直链)
 * 3. 播放       → m3u8 直链可直接播放(无需 Referer, 已验证)
 */
object SusouApi {

    // ===== 密钥与服务器常量 (自用, 不公开) =====
    private const val AES_BIZ_KEY = "asdfghjklmnbvcxz"
    private const val HS_KEY = "7gp0bnd2sr85ydii2j32pcypscoc4w6c7g5spl" // 备用源签名

    // 速搜自家服务器 (主 2233, 镜像 18285/24999/19446)
    private val BIZ_PORTS = listOf("2233", "18285", "24999", "19446")
    private const val BIZ_HOST = "43.248.128.251"

    private const val RBOTV_BASE = "http://v.rbotv.cn/v3/home"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ===== 内存缓存 (TTL)：减少重复搜索/详情请求，降低被源站限流风险 =====
    private class TtlCache {
        private val map = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Any?>>()

        @Suppress("UNCHECKED_CAST")
        fun <V> get(key: String, ttlMs: Long): V? {
            val entry = map[key] ?: return null
            if (System.currentTimeMillis() - entry.first > ttlMs) {
                map.remove(key)
                return null
            }
            return entry.second as? V
        }

        fun put(key: String, value: Any?) {
            map[key] = System.currentTimeMillis() to value
        }
    }

    private val ttlCache = TtlCache()

    // ===== 工具: MD5 =====
    private fun md5(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ===== 工具: AES/ECB/Pkcs7 解密 =====
    fun aesDecryptBase64(base64Str: String, key: String = AES_BIZ_KEY): String {
        val raw = android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
        return String(cipher.doFinal(raw), Charsets.UTF_8)
    }

    // ===== 请求执行 (多端口容错) =====
    private fun exec(request: Request): String {
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return body
        }
    }

    // ========== 1. 速搜自家: 搜索 (明文) ==========
    suspend fun search(keyword: String): List<SusouVideoItem> =
        withContext(Dispatchers.IO) {
            ttlCache.get<List<SusouVideoItem>>("ss_$keyword", 60_000)?.let { return@withContext it }
            var lastErr: Exception? = null
            for (port in BIZ_PORTS) {
                try {
                    val url = "http://$BIZ_HOST:$port/api.php/app/search?text=" +
                        java.net.URLEncoder.encode(keyword, "UTF-8")
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "okhttp/4.9.2")
                        .get()
                        .build()
                    val body = exec(request)
                    val resp = json.decodeFromString(SusouSearchResponse.serializer(), body)
                    if (resp.code == 1 && resp.list.isNotEmpty()) {
                        ttlCache.put("ss_$keyword", resp.list)
                        return@withContext resp.list
                    }
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            throw lastErr ?: RuntimeException("速搜搜索失败")
        }

    // ========== 2. 备用源: 搜索 (MD5 签名) ==========
    suspend fun rbotvSearch(keyword: String): List<RbotvVideoItem> =
        withContext(Dispatchers.IO) {
            ttlCache.get<List<RbotvVideoItem>>("rbotv_$keyword", 120_000)?.let { return@withContext it }
            val ts = System.currentTimeMillis() / 1000
            val sign = md5(HS_KEY + ts)
            val form = FormBody.Builder()
                .add("sign", sign)
                .add("timestamp", ts.toString())
                .add("keyword", keyword)
                .build()
            val request = Request.Builder()
                .url("$RBOTV_BASE/search")
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .header("User-Agent", "okhttp-okgo/jeasonlzy")
                .header("X-Requested-With", "com.sjz.ss")
                .post(form)
                .build()
            val body = exec(request)
            val resp = json.decodeFromString(RbotvSearchResponse.serializer(), body)
            val list = resp.data?.list ?: emptyList()
            if (list.isNotEmpty()) ttlCache.put("rbotv_$keyword", list)
            list
        }

    // ========== 3. 备用源: 详情 ep 列表 (MD5 签名) ==========
    suspend fun rbotvDetail(vodId: Int): RbotvDetailData? =
        withContext(Dispatchers.IO) {
            ttlCache.get<RbotvDetailData>("rbotvd_$vodId", 300_000)?.let { return@withContext it }
            val ts = System.currentTimeMillis() / 1000
            val sign = md5(HS_KEY + ts)
            val form = FormBody.Builder()
                .add("sign", sign)
                .add("timestamp", ts.toString())
                .add("vod_id", vodId.toString())
                .build()
            val request = Request.Builder()
                .url("$RBOTV_BASE/vod_details")
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .header("User-Agent", "okhttp-okgo/jeasonlzy")
                .header("X-Requested-With", "com.sjz.ss")
                .post(form)
                .build()
            val body = exec(request)
            val resp = json.decodeFromString(RbotvDetailResponse.serializer(), body)
            resp.data?.also { ttlCache.put("rbotvd_$vodId", it) }
        }

    // ========== 4. 速搜自家: weekday 周期表 (AES 解密) ==========
    suspend fun weekday(): List<SusouVideoItem> =
        withContext(Dispatchers.IO) {
            ttlCache.get<List<SusouVideoItem>>("weekday", 300_000)?.let { return@withContext it }
            var lastErr: Exception? = null
            for (port in BIZ_PORTS) {
                try {
                    val url = "http://$BIZ_HOST:$port/api.php/app/weekday"
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "okhttp/4.9.2")
                        .get()
                        .build()
                    val body = exec(request)
                    val pt = aesDecryptBase64(body)
                    val resp = json.decodeFromString(SusouSearchResponse.serializer(), pt)
                    if (resp.code == 1 && resp.list.isNotEmpty()) {
                        ttlCache.put("weekday", resp.list)
                        return@withContext resp.list
                    }
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            throw lastErr ?: RuntimeException("速搜周期表获取失败")
        }

    // ========== 5. 备用源反查: 按剧名取详情(速搜自家 video_detail 已删, ep 走备用源) ==========
    /**
     * 速搜自家 video_detail 接口被管理员删除(404)，ep 列表经备用源按剧名反查。
     * 流程: rbotvSearch(剧名) → 取第一条 vod_id → rbotvDetail(vodId) → 多线路 m3u8 直链
     */
    suspend fun fetchDetailByKeyword(keyword: String): Pair<VideoItem, List<VideoSource>>? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ttlCache.get<Pair<VideoItem, List<VideoSource>>>("fdet_$keyword", 300_000)?.let {
                return@withContext it
            }
            try {
                val searchResults = rbotvSearch(keyword)
                val target = searchResults.firstOrNull() ?: return@withContext null
                val detail = rbotvDetail(target.vodId) ?: return@withContext null

                val video = VideoItem(
                    id = detail.vodId,
                    name = detail.vodName.ifBlank { keyword },
                    pic = detail.vodPic.ifBlank { target.vodPic },
                    remarks = detail.vodRemarks,
                    actor = detail.vodActor,
                    director = detail.vodDirector,
                    content = detail.vodContent,
                    area = detail.vodArea,
                    lang = detail.vodLang,
                    year = detail.vodYear,
                    score = detail.vodScore
                )

                val sources = detail.vodPlayList.mapNotNull { pl ->
                    val parseUrls = pl.parseUrls
                    val eps = pl.urls.mapNotNull { ep ->
                        val url = ep.url.trim()
                        when {
                            url.isBlank() -> null
                            // NBY 加密地址: 保留原串 + 附带解密接口前缀, 播放时懒解密(预解密会因 time 参数过期)
                            url.startsWith("NBY-") -> Episode(name = ep.name, url = url, parseUrls = parseUrls)
                            url.startsWith("http") -> Episode(name = ep.name, url = url)
                            else -> null
                        }
                    }
                    if (eps.isEmpty()) null else VideoSource(label = friendlySourceName(pl.flag, pl.name), episodes = eps)
                }
                if (sources.isEmpty()) return@withContext null
                (video to sources).also { ttlCache.put("fdet_$keyword", it) }
            } catch (e: Exception) {
                null
            }
        }
    }

    // ========== 6. NBY 加密地址解密 (备用源 parse_urls 接口) ==========
    /**
     * 备用源 NBY 线路返回 `NBY-XMYAES<hex>|<key>` 加密串, ExoPlayer 无法直接播放。
     * 该线路自带 parse_urls 解密接口(如 https://api.nbyjson.top:7788/api/?key=xxx&url=):
     * GET parseUrl + urlencode(NBY密文串) → {"code":200,"url":"http://...m3u8?...","type":"hls"}
     * 返回的 url 有时效(time 参数), 需立即播放; 失效时接口返回牛图页面(JSON 解析失败→null)。
     */
    suspend fun resolveNby(nbyUrl: String, parseUrls: List<String>): String? =
        withContext(Dispatchers.IO) {
            val base = parseUrls.firstOrNull() ?: return@withContext null
            try {
                val full = base + java.net.URLEncoder.encode(nbyUrl, "UTF-8")
                val request = Request.Builder().url(full).get().build()
                val body = exec(request)
                val resp = json.decodeFromString(NbyResolveResponse.serializer(), body)
                if (resp.code == 200) resp.url.takeIf { it.isNotBlank() } else null
            } catch (e: Exception) {
                null
            }
        }
}

/**
 * 速搜详情导航暂存：DetailScreen 路由参数只传 videoId，
 * 大对象(VideoItem + sources) 经此单例中转，进入详情页时一次性取走。
 */
object SusouNavHolder {
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
