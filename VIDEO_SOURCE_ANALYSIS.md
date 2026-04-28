# 视频源完整分析报告

## 1. API 基本信息

- API: `https://cj.lziapi.com/api.php/provide/vod/`
- Referer: `https://cj.lziapi.com/`
- 所有视频都有两个播放源：`liangzi` 和 `lzm3u8`

## 2. 两个播放源

### Source0 (liangzi) — HTML 播放器页面（无用）
- URL 格式：`https://v.lz15uu.com/share/{hash}`
- 返回：HTML 页面，包含 ckplayer/artplayer 播放器
- 页面内嵌变量：`var main = "/20260414/{id}/index.m3u8?sign={sign}"`
- 本质：最终还是指向 Source1 的 M3U8 地址
- **不能用于 DLNA 投屏**

### Source1 (lzm3u8) — M3U8 HLS 流（实际使用）
- URL 格式：`https://v.lz15uu.com/{date}/{id}/index.m3u8`
- 有可选 sign 参数，但不影响访问（不带 sign 也能正常获取）

## 3. M3U8 结构

### Master Playlist (`index.m3u8`)
```
#EXTM3U
#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=800000,RESOLUTION=1080x608
2000k/hls/mixed.m3u8
```
- 只有 1 个码率变体
- 声明分辨率 1080x608，实际分段是 1280x720

### Media Playlist (`2000k/hls/mixed.m3u8`)
```
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-PLAYLIST-TYPE:VOD
#EXT-X-MEDIA-SEQUENCE:0
#EXT-X-TARGETDURATION:8
```

关键特征：
- **没有** `#EXT-X-MAP` 标签 → **不是 fMP4**
- **没有** `#EXT-X-KEY` 标签 → **没有加密**
- 分段扩展名 `.ts` → **MPEG-TS 格式**
- 有大量 `#EXT-X-DISCONTINUITY` 标签（每 8-10 个分段一组）
- 总共 302 个分段，总时长约 1211 秒（~20 分钟）

## 4. 分段分析

### 单个分段格式
- **MPEG-TS** 确认（sync byte 0x47）
- 编码：H.264 High Profile, Level 3.1
- 分辨率：1280x720（16:9）
- 帧率：25fps
- 音频：AAC-LC, 44100Hz, 立体声, ~132kbps
- 每个 TS 分段包含完整的 PAT/PMT 表（可独立解码）
- 元数据标记为 FFmpeg 生成

### 分段大小示例
| 分段 | 大小 |
|---|---|
| seg0 | 837KB |
| seg1 | 1132KB |
| 平均 | ~300-1100KB（取决于画面复杂度）|

### DISCONTINUITY 分析
- 每 8-10 个分段有一个 `#EXT-X-DISCONTINUITY` 标记
- 这意味着 PTS（时间戳）在边界处会跳变
- 简单拼接可能导致某些播放器问题
- ffmpeg 仍然可以正确处理跨边界拼接

### 拼接测试结果
- **同组内拼接**：ffmpeg 完美识别，10.14 秒，H.264+AAC
- **跨边界拼接**：ffmpeg 也能处理，41.22 秒
- **TS→MP4 转换**（`-c copy`）：成功，无重编码，probe_score=100（满分）


