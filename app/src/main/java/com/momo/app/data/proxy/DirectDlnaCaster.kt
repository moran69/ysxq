package com.momo.app.data.proxy

import android.util.Log
import com.yinnho.upnpcast.DLNACast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Direct DLNA SOAP caster — bypasses upnpcast's buggy DlnaMediaController.
 *
 * Uses upnpcast only for device discovery (DLNACast.search), then sends
 * our own SOAP SetAVTransportURI + Play commands with proper DIDL-Lite metadata.
 *
 * Key fixes vs upnpcast:
 * 1. DLNA.ORG_PN in protocolInfo (many TVs require this)
 * 2. Parses SOAP response body to detect errors (upnpcast doesn't)
 * 3. Retries with different metadata formats
 * 4. Detailed logging of every SOAP request/response
 */
object DirectDlnaCaster {

    private const val TAG = "DirectDlnaCaster"
    private const val SOAP_TIMEOUT_CONNECT = 5000
    private const val SOAP_TIMEOUT_READ = 10000

    @Volatile
    private var cachedControlUrl: String? = null

    fun cacheControlUrl(url: String) {
        cachedControlUrl = url
    }

    fun clearCache() {
        cachedControlUrl = null
    }

    /**
     * Cast a video URL to a DLNA device.
     *
     * @param device The device found via DLNACast.search()
     * @param videoUrl The video URL (proxy URL from DlnaProxyServer)
     * @param title Video title for TV display
     * @return true if the TV accepted the URL and started playing
     */
    suspend fun cast(device: DLNACast.Device, videoUrl: String, title: String): Boolean =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "=== Casting to ${device.name} (${device.address}) ===")
            Log.i(TAG, "URL: $videoUrl")

            // Step 1: Find AVTransport control URL from device
            val controlUrl = findAvTransportControlUrl(device)
            if (controlUrl == null) {
                Log.e(TAG, "FAILED: Could not find AVTransport control URL for device ${device.name}")
                Log.e(TAG, "This usually means the device description XML couldn't be parsed.")
                return@withContext false
            }
            Log.i(TAG, "AVTransport control URL: $controlUrl")
            cacheControlUrl(controlUrl)

            // Step 2: Send SetAVTransportURI with proper DIDL metadata
            val didl = buildDidlMetadata(title, videoUrl)
            Log.d(TAG, "DIDL metadata:\n$didl")

            val setUriResult = sendSetAvTransportUri(controlUrl, videoUrl, didl)
            Log.i(TAG, "SetAVTransportURI result: $setUriResult")

            if (!setUriResult.success) {
                Log.e(TAG, "SetAVTransportURI SOAP error: ${setUriResult.errorDetail}")

                // Retry with minimal metadata (some TVs don't like rich DIDL)
                Log.i(TAG, "Retrying with minimal metadata...")
                val minimalDidl = buildMinimalDidl(title, videoUrl)
                val retryResult = sendSetAvTransportUri(controlUrl, videoUrl, minimalDidl)
                Log.i(TAG, "Retry SetAVTransportURI result: ${retryResult.success}")
                if (!retryResult.success) {
                    Log.e(TAG, "Retry also failed: ${retryResult.errorDetail}")
                    return@withContext false
                }
            }

            // Step 3: Send Play
            delay(300) // Small delay between SetURI and Play
            val playResult = sendPlay(controlUrl)
            Log.i(TAG, "Play result: ${playResult.success}")

            if (!playResult.success) {
                Log.e(TAG, "Play SOAP error: ${playResult.errorDetail}")
            }

            playResult.success
        }

    private fun findAvTransportControlUrl(device: DLNACast.Device): String? {
        // device.id is the SSDP LOCATION URL (e.g. http://192.168.1.100:49152/description.xml)
        // We fetch the device description XML and parse out the AVTransport controlURL
        val locationUrl = device.id
        if (locationUrl.startsWith("http://")) {
            val parsed = parseControlUrlFromDescription(locationUrl)
            if (parsed != null) {
                Log.i(TAG, "Found control URL from device description: $parsed")
                return parsed
            }
        }

        Log.w(TAG, "Falling back to URL probing for ${device.name}")
        return probeControlUrl(device.address)
    }

    private fun parseControlUrlFromDescription(locationUrl: String): String? {
        return try {
            val url = URL(locationUrl)
            val port = if (url.port > 0) ":${url.port}" else ""
            val baseUrl = "http://${url.host}$port"

            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"

            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }

            val xml = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            // Extract AVTransport service's controlURL from device description XML
            val servicePattern = Regex("<service>(.*?)</service>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            for (match in servicePattern.findAll(xml)) {
                val serviceXml = match.groupValues[1]
                val serviceType = extractXmlValue(serviceXml, "serviceType") ?: continue
                if (serviceType.contains("AVTransport", ignoreCase = true)) {
                    val controlPath = extractXmlValue(serviceXml, "controlURL") ?: continue
                    // controlURL 可能是绝对 http(s)、/ 开头相对路径，或 _urn:... 这类相对路径(如 Windows Media Player)。
                    // 统一按 UPnP 规范相对 URLBase 解析; 拼接时务必保证 host:port 与路径间有 /
                    val controlUrl = when {
                        controlPath.startsWith("http", ignoreCase = true) -> controlPath
                        controlPath.startsWith("/") -> "$baseUrl$controlPath"
                        else -> "$baseUrl/$controlPath"
                    }
                    try {
                        URL(controlUrl)
                    } catch (e: Exception) {
                        Log.w(TAG, "Skip malformed controlURL: $controlUrl (${e.message})")
                        continue
                    }
                    return controlUrl
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse device description from $locationUrl: ${e.message}")
            null
        }
    }

    private fun extractXmlValue(xml: String, tagName: String): String? {
        val startTag = "<$tagName"
        val endTag = "</$tagName>"
        val startIdx = xml.indexOf(startTag, ignoreCase = true)
        if (startIdx == -1) return null
        val tagEndIdx = xml.indexOf('>', startIdx)
        if (tagEndIdx == -1) return null
        val endIdx = xml.indexOf(endTag, tagEndIdx, ignoreCase = true)
        if (endIdx == -1) return null
        return xml.substring(tagEndIdx + 1, endIdx).trim().ifBlank { null }
    }

    private fun probeControlUrl(ip: String): String? {
        val paths = listOf(
            "/upnp/control/AVTransport",
            "/upnp/control/AVTransport1",
            "/AVTransport/control",
            "/AVTransport/control/",
            "/ctl/AVTransport",
            "/control/AVTransport",
            "/AVTransport",
            "/upnp/AVTransport",
            "/scpd/AVTransport",
            "/AVTransport/ctrl",
            "/AVTransport/Control",
        )
        // 除固定端口外, 也尝试常见范围
        val ports = listOf(49152, 49153, 49154, 49155, 5000, 8080, 2869, 1400, 9000)
        for (port in ports) {
            for (path in paths) {
                val url = "http://$ip:$port$path"
                if (testUrl(url)) return url
            }
        }
        return null
    }

    /**
     * Test if a URL responds to a SOAP request (quick probe)
     */
    private fun testUrl(url: String): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            conn.setRequestProperty("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo\"")
            conn.doOutput = true
            conn.connectTimeout = 2000
            conn.readTimeout = 2000

            val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:GetTransportInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <InstanceID>0</InstanceID>
        </u:GetTransportInfo>
    </s:Body>
</s:Envelope>"""

            conn.outputStream.use { os ->
                OutputStreamWriter(os, "UTF-8").use { it.write(body); it.flush() }
            }

            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Build full DIDL-Lite metadata with DLNA protocol info.
     * Uses DLNA.ORG_PN=MPEG_TS for maximum TV compatibility.
     */
    private fun buildDidlMetadata(title: String, videoUrl: String): String {
        val safeTitle = escapeXml(title)
        val safeUrl = escapeXml(videoUrl)

        // Proxy serves M3U8 playlist but actual segment content is MPEG-TS.
        // Use video/mpeg with DLNA.ORG_PN for maximum TV compatibility.
        val protocolInfo = when {
            videoUrl.contains(".mp4", ignoreCase = true) ->
                "http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_MP_SD_AAC_MULT5;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            else ->
                "http-get:*:video/mpeg:DLNA.ORG_PN=AVC_TS_HD_NA_ISO;DLNA.ORG_OP=11;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        }

        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:dlna="urn:schemas-dlna-org:metadata-1-0/">
<item id="0" parentID="-1" restricted="1">
<dc:title>$safeTitle</dc:title>
<upnp:class>object.item.videoItem</upnp:class>
<res protocolInfo="$protocolInfo">$safeUrl</res>
</item>
</DIDL-Lite>"""
    }

    /**
     * Build minimal DIDL-Lite metadata (fallback for picky TVs).
     */
    private fun buildMinimalDidl(title: String, videoUrl: String): String {
        val safeTitle = escapeXml(title)
        val safeUrl = escapeXml(videoUrl)
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
<item id="0" parentID="-1" restricted="1">
<dc:title>$safeTitle</dc:title>
<upnp:class>object.item.videoItem</upnp:class>
<res protocolInfo="http-get:*:video/mpeg:*">$safeUrl</res>
</item>
</DIDL-Lite>"""
    }

    /**
     * Send SetAVTransportURI SOAP action.
     */
    private fun sendSetAvTransportUri(
        controlUrl: String,
        mediaUrl: String,
        metadata: String
    ): SoapResult {
        val soapBody = """
            <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
                <CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>
                <CurrentURIMetaData><![CDATA[$metadata]]></CurrentURIMetaData>
            </u:SetAVTransportURI>
        """.trimIndent()

        return sendSoapRequest(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", soapBody)
    }

    /**
     * Send Play SOAP action.
     */
    private fun sendPlay(controlUrl: String): SoapResult {
        val soapBody = """
            <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
                <Speed>1</Speed>
            </u:Play>
        """.trimIndent()

        return sendSoapRequest(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#Play", soapBody)
    }

    /**
     * Pause playback on the TV via DLNA Pause SOAP action.
     */
    suspend fun pause(): Boolean = withContext(Dispatchers.IO) {
        val controlUrl = cachedControlUrl ?: return@withContext false
        val soapBody = """
            <u:Pause xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
            </u:Pause>
        """.trimIndent()

        val result = sendSoapRequest(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#Pause", soapBody)
        Log.i(TAG, "Pause result: ${result.success}")
        result.success
    }

    /**
     * Resume/start playback on the TV via DLNA Play SOAP action.
     */
    suspend fun play(): Boolean = withContext(Dispatchers.IO) {
        val controlUrl = cachedControlUrl ?: return@withContext false
        val result = sendPlay(controlUrl)
        Log.i(TAG, "Play result: ${result.success}")
        result.success
    }

    suspend fun stop(device: DLNACast.Device): Boolean = withContext(Dispatchers.IO) {
        val controlUrl = cachedControlUrl ?: findAvTransportControlUrl(device) ?: return@withContext false
        val soapBody = """
            <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
            </u:Stop>
        """.trimIndent()
        val result = sendSoapRequest(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#Stop", soapBody)
        Log.i(TAG, "Stop result: ${result.success}")
        result.success
    }

    /**
     * Seek to a specific position on the TV via DLNA Seek SOAP action.
     * Sends REL_TIME seek with position formatted as HH:MM:SS.
     */
    suspend fun seek(positionMs: Long): Boolean = withContext(Dispatchers.IO) {
        val controlUrl = cachedControlUrl ?: return@withContext false
        val totalSeconds = positionMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val timeString = "%02d:%02d:%02d".format(hours, minutes, seconds)

        Log.i(TAG, "Seeking to $timeString ($positionMs ms)")

        val soapBody = """
            <u:Seek xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
                <Unit>REL_TIME</Unit>
                <Target>$timeString</Target>
            </u:Seek>
        """.trimIndent()

        val result = sendSoapRequest(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#Seek", soapBody)
        Log.i(TAG, "Seek result: ${result.success}")
        result.success
    }

    /**
     * Send a SOAP request and parse the response.
     */
    private fun sendSoapRequest(url: String, soapAction: String, body: String): SoapResult {
        val envelope = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        $body
    </s:Body>
</s:Envelope>"""

        Log.d(TAG, "SOAP POST $url")
        Log.d(TAG, "SOAPAction: $soapAction")

        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty("SOAPAction", "\"$soapAction\"")
            connection.setRequestProperty("User-Agent", "UPnP/1.0 DLNADOC/1.50")
            connection.doOutput = true
            connection.connectTimeout = SOAP_TIMEOUT_CONNECT
            connection.readTimeout = SOAP_TIMEOUT_READ

            connection.outputStream.use { os ->
                OutputStreamWriter(os, "UTF-8").use { writer ->
                    writer.write(envelope)
                    writer.flush()
                }
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode == 200) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) { "" }
            }

            Log.d(TAG, "SOAP response code: $responseCode")
            Log.d(TAG, "SOAP response body: ${responseBody.take(500)}")

            if (responseCode != 200) {
                return SoapResult(false, "HTTP $responseCode: ${responseBody.take(200)}", responseBody)
            }

            // Check for SOAP fault even in HTTP 200 responses
            if (responseBody.contains("s:Fault", ignoreCase = true) ||
                responseBodyBodyContains(responseBody, "errorCode", ignoreCase = true)) {
                val errorDesc = extractSoapError(responseBody)
                return SoapResult(false, "SOAP Fault: $errorDesc", responseBody)
            }

            return SoapResult(true, null, responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "SOAP request exception: ${e.message}", e)
            return SoapResult(false, "Exception: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun responseBodyBodyContains(text: String, token: String, ignoreCase: Boolean = false): Boolean {
        return text.contains(token, ignoreCase = ignoreCase)
    }

    private fun extractSoapError(response: String): String {
        val errorDesc = Regex("""<errorDescription>(.*?)</errorDescription>""", RegexOption.IGNORE_CASE)
            .find(response)?.groupValues?.get(1)
        val errorCode = Regex("""<errorCode>(.*?)</errorCode>""", RegexOption.IGNORE_CASE)
            .find(response)?.groupValues?.get(1)

        return buildString {
            if (errorCode != null) append("code=$errorCode ")
            if (errorDesc != null) append(errorDesc)
            if (errorCode == null && errorDesc == null) append(response.take(200))
        }
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private suspend fun delay(ms: Long) = kotlinx.coroutines.delay(ms)

    data class SoapResult(
        val success: Boolean,
        val errorDetail: String?,
        val rawBody: String? = null
    )

    /**
     * Query TV's current transport state via GetTransportInfo SOAP action.
     * Returns: PLAYING, PAUSED, STOPPED, BUFFERING, ERROR, or null on failure.
     */
    suspend fun getTransportState(): String? = withContext(Dispatchers.IO) {
        val controlUrl = cachedControlUrl ?: return@withContext null
        val soapBody = """
            <u:GetTransportInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
            </u:GetTransportInfo>
        """.trimIndent()

        val result = sendSoapRequest(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo", soapBody)
        if (!result.success) {
            Log.w(TAG, "GetTransportInfo failed: ${result.errorDetail}")
            return@withContext null
        }

        // Parse <CurrentTransportState>PLAYING</CurrentTransportState>
        val state = extractXmlValue(result.rawBody ?: "", "CurrentTransportState")
        Log.d(TAG, "Transport state: $state")
        state
    }

    /**
     * Query TV's current playback position via GetPositionInfo SOAP action.
     * Returns Pair(currentMs, totalMs) or null on failure.
     */
    suspend fun getPositionInfo(): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val controlUrl = cachedControlUrl ?: return@withContext null
        val soapBody = """
            <u:GetPositionInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
            </u:GetPositionInfo>
        """.trimIndent()

        val result = sendSoapRequest(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo", soapBody)
        if (!result.success) {
            Log.w(TAG, "GetPositionInfo failed: ${result.errorDetail}")
            return@withContext null
        }

        val raw = result.rawBody ?: return@withContext null
        val relTime = extractXmlValue(raw, "RelTime") // e.g. "00:05:23"
        val duration = extractXmlValue(raw, "Duration") // e.g. "01:25:00"

        val currentMs = parseTimeToMs(relTime)
        val totalMs = parseTimeToMs(duration)

        if (currentMs != null && totalMs != null && totalMs > 0) {
            Log.d(TAG, "Position: ${relTime ?: "?"} / ${duration ?: "?"} (${currentMs}ms / ${totalMs}ms)")
            Pair(currentMs, totalMs)
        } else {
            null
        }
    }

    private fun parseTimeToMs(time: String?): Long? {
        if (time.isNullOrBlank() || time == "NOT_IMPLEMENTED" || time == "00:00:00.000") return null
        val parts = time.split(":")
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].substringBefore(".").toLongOrNull() ?: return null
        return (hours * 3600 + minutes * 60 + seconds) * 1000
    }
}
