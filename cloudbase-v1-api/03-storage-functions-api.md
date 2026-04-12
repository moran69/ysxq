# CloudBase v1 Storage & Cloud Functions API — 云存储与云函数完整文档

**OpenAPI Spec (Storage)**: https://docs.cloudbase.net/openapi/en/storage.v1.openapi.yaml
**OpenAPI Spec (Functions)**: https://docs.cloudbase.net/openapi/en/functions.v1.openapi.yaml

**认证**: `Authorization: Bearer <access_token>`

---

## 云存储通用错误码

| 错误码 | 说明 |
|---|---|
| `STORAGE_EXCEED_AUTHORITY` | 无权限操作此对象 |
| `COS_ACTION_FAILED` | 对象存储操作失败 |
| `OBJECT_NOT_EXIST` | 对象不存在 |
| `OBJECT_ALREADY_EXIST` | 对象已存在 |
| `OBJECT_BATCH_TOO_LARGE` | 单次批量处理数量过多 |
| `ACTION_FORBIDDEN` | 身份鉴权拦截 |

---

## 1. 获取对象上传信息 — POST /v1/storages/get-objects-upload-info

**文档**: https://docs.cloudbase.net/en/http-api/storage/get-objects-upload-info

⚠️ 单文件上传有 **5GB** 大小限制。

**此接口受云存储桶权限配置和身份认证控制。**

### 请求体 (JSON 数组)
```json
[
  {
    "objectId": "filename.jpg"
  }
]
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `objectId` | string | **是** | 对象 ID，例如文件名 |
| `signedHeader` | object | 否 | 额外签名请求头 |

### 响应 200 (JSON 数组)
```json
[
  {
    "objectId": "dirname/filename.jpg",
    "uploadUrl": "https://url/filename.jpg",
    "authorization": "q-sign-algorithm=sha1&q-ak=...",
    "token": "xxx",
    "cloudObjectMeta": "xxx",
    "downloadUrl": "https://url/filename.jpg?sign=xxx&t=yyy",
    "downloadUrlEncoded": "https://url/filename.jpg?sign=xxx&t=yyy",
    "cloudObjectId": "cloud://your-envId.bucket/filename.jpg"
  },
  {
    "code": "COS_ACTION_FAILED",
    "message": "Execute COS action failed."
  }
]
```

### 上传步骤

**Step 1**: 调用此 API 获取上传信息

**Step 2**: 使用返回字段上传文件（PUT 请求）:
```bash
curl -X PUT "$uploadUrl" \
  -H "Authorization: $auth" \
  -H "X-Cos-Security-Token: $token" \
  -H "X-Cos-Meta-Fileid: $meta" \
  --data-binary @filename.jpg
```

### 成功项字段
| 字段 | 说明 |
|---|---|
| `uploadUrl` | 上传目标 URL |
| `downloadUrl` | 上传后的下载地址 |
| `downloadUrlEncoded` | URL 编码的下载地址 |
| `token` | 填入 `X-Cos-Security-Token` 请求头 |
| `authorization` | 填入 `Authorization` 请求头 |
| `cloudObjectMeta` | 填入 `X-Cos-Meta-Fileid` 请求头 |
| `cloudObjectId` | 云端对象 ID（`cloud://envId.bucket/path`） |
| `objectId` | 本请求的对象 ID |

---

## 2. 获取对象下载信息 — POST /v1/storages/get-objects-download-info

**文档**: https://docs.cloudbase.net/en/http-api/storage/get-objects-download-info

### 请求体 (JSON 数组)
```json
[
  {
    "cloudObjectId": "cloud://your-envId.bucket/file.jpg"
  }
]
```

也可以传纯字符串数组:
```json
["cloud://your-envId.bucket/file.jpg"]
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `cloudObjectId` | string | **是** | 云端对象 ID（`cloud://...`） |

### 响应 200 (JSON 数组)
```json
[
  {
    "cloudObjectId": "cloud://your-envId.bucket/file.jpg",
    "downloadUrl": "https://url/file.jpg",
    "downloadUrlEncoded": "https://url/file.jpg"
  },
  {
    "code": "INVALID_PARAM",
    "message": "Invalid request param."
  }
]
```

### 成功项字段
| 字段 | 说明 |
|---|---|
| `cloudObjectId` | 云端对象 ID |
| `downloadUrl` | 下载 URL（未 URL 编码） |
| `downloadUrlEncoded` | 下载 URL（已 URL 编码） |

⚠️ 下载 URL 是临时链接，会过期。

---

## 3. 删除对象 — POST /v1/storages/delete-objects

**文档**: https://docs.cloudbase.net/en/http-api/storage/delete-objects

### 请求体 (JSON 数组)
```json
[
  {
    "cloudObjectId": "cloud://your-envId.bucket/file.jpg"
  }
]
```

### 响应 200 (JSON 数组)
```json
[
  {
    "cloudObjectId": "cloud://your-envId.bucket/not-exist",
    "code": "OBJECT_NOT_EXIST",
    "message": "Storage object not exists."
  },
  {
    "cloudObjectId": "cloud://your-envId.bucket/file.jpg"
  }
]
```

成功项仅含 `cloudObjectId`，错误项额外含 `code` + `message`。

---

## 4. 复制对象 — POST /v1/storages/copy-objects

**文档**: https://docs.cloudbase.net/en/http-api/storage/copy-objects

⚠️ **此接口仅管理员可调用。**

### 请求体 (JSON 数组)
```json
[
  {
    "srcPath": "/source/path/file.jpg",
    "dstPath": "/dest/path/file.jpg",
    "overwrite": true,
    "removeOriginal": false
  }
]
```

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `srcPath` | string | **是** | — | 源对象绝对路径（含文件名） |
| `dstPath` | string | **是** | — | 目标对象绝对路径（含文件名） |
| `overwrite` | boolean | 否 | `true` | 是否覆盖已存在的目标 |
| `removeOriginal` | boolean | 否 | `false` | 复制后是否删除原对象 |

### 响应 200
```json
[
  {
    "cloudObjectId": "cloud://your-envId.bucket/file.jpg"
  }
]
```

---

## 5. 调用云函数 — POST /v1/functions/:name

**文档**: https://docs.cloudbase.net/en/http-api/functions/functions-post

### 路径参数
| 参数 | 类型 | 说明 |
|---|---|---|
| `name` | string | 函数名，正则 `^[a-zA-Z][a-zA-Z0-9_-]{0,58}[a-zA-Z0-9]$` |

### Query 参数
| 参数 | 值 | 说明 |
|---|---|---|
| `webfn` | `true` | 调用 web 函数时设置 |

### Header 参数
| Header | 值 | 说明 |
|---|---|---|
| `X-Qualifier` | string | 指定函数版本，不填按灰度规则 |
| `X-Tcb-Webfn` | `true` | 调用 web 函数的替代方式 |

### 请求体
任意 JSON 对象，作为函数入参。

### 响应 200
动态内容，取决于函数返回值。

### 响应 400（调用失败）
```json
{
  "code": "FUNCTION_PARAM_INVALID",
  "message": "bad http request body (...)",
  "requestId": "..."
}
```

### 云函数错误码

| 错误码 | 说明 |
|---|---|
| `FUNCTION_NOT_FOUND` | 未找到指定函数 |
| `FUNCTION_QUALIFIER_NOT_FOUND` | 未找到指定版本 |
| `FUNCTION_INVOCATION_FAILED` | 函数执行失败 |
| `FUNCTION_PARAM_INVALID` | 参数无效 |
| `FUNCTION_EXCEED_RESOURCE_LIMIT` | 超过预置并发 |
| `FUNCTION_STATUS_ABNORMAL` | 函数状态异常 |
| `FUNCTION_TIME_LIMIT_EXCEEDED` | 执行超时 |
| `FUNCTION_MEMORY_LIMIT_EXCEEDED` | 内存超限 |

---

## App 端点 → 文档映射

| App 端点 | 官方文档 |
|---|---|
| `v1/storages/get-objects-download-info` | [get-objects-download-info](https://docs.cloudbase.net/en/http-api/storage/get-objects-download-info) |
| `v1/functions/{name}` | [functions-post](https://docs.cloudbase.net/en/http-api/functions/functions-post) |

---

## 完整 API 端点汇总

| 方法 | 端点 | 说明 |
|---|---|---|
| `POST` | `/v1/storages/get-objects-upload-info` | 获取上传信息 |
| `POST` | `/v1/storages/get-objects-download-info` | 获取下载链接 |
| `POST` | `/v1/storages/delete-objects` | 删除对象 |
| `POST` | `/v1/storages/copy-objects` | 复制对象（仅管理员） |
| `POST` | `/v1/functions/:name` | 调用云函数 |
