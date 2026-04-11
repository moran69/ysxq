# CloudBase v1 HTTP API — 总览与认证

**Base URL**: `https://{envId}.api.tcloudbasegateway.com`（国内）
**Base URL**: `https://{envId}.api.intl.tcloudbasegateway.com`（海外）

## 认证方式

请求头: `Authorization: Bearer <access_token/apikey/publishable_key>`

### 三种令牌类型

| 令牌 | 适用环境 | 权限 | 有效期 | 获取方式 |
|---|---|---|---|---|
| Access_token | 客户端/服务端 | 登录用户 | 默认 2 小时 | 登录接口获取 |
| API Key | 仅服务端 | 管理员 | 永久 | 控制台 API Key 管理页 |
| Publishable Key | 客户端/服务端 | 匿名用户 | 永久 | 控制台 API Key 管理页 |

⚠️ API Key 严禁客户端使用。Data Model 和 Auth 接口不支持腾讯云签名 V3。

## API 导航

| 类别 | 路径前缀 |
|---|---|
| 认证 | `auth/v1/...` |
| 数据模型 | `v1/model/prod/{modelName}/...` |
| 云存储 | `v1/storages/...` |
| 云函数 | `v1/functions/{name}` |

## 错误响应格式

```json
{
  "code": "ERROR_CODE",
  "message": "Error description",
  "requestId": "unique-request-id"
}
```

## 官方文档链接

| 页面 | URL |
|---|---|
| 概述 | https://docs.cloudbase.net/http-api/basic/overview |
| AccessToken | https://docs.cloudbase.net/http-api/basic/access-token |
| 认证接口 | https://docs.cloudbase.net/http-api/auth/登录认证接口 |
| 数据模型 | https://docs.cloudbase.net/http-api/model/数据模型-openapi |
| 云存储 | https://docs.cloudbase.net/http-api/storage/云存储 |
| 云函数 | https://docs.cloudbase.net/http-api/functions/云函数 |
| OpenAPI Spec | https://docs.cloudbase.net/openapi/en/datasource.v1.openapi.yaml |
