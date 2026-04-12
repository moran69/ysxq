# CloudBase v1 Auth API — 认证接口完整文档

**OpenAPI Spec**: https://docs.cloudbase.net/openapi/auth.v1.openapi.yaml

---

## 1. 发送验证码 — POST /auth/v1/verification

**文档**: https://docs.cloudbase.net/en/http-api/auth/auth-send-verification

### 请求头
| Header | 必填 | 说明 |
|---|---|---|
| `x-captcha-token` | 否 | 返回 captcha_required 时需要 |

### 请求体 (JSON)
| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `phone_number` | string | 否* | 手机号，格式 `+86 15588665555`，与 email 二选一 |
| `email` | string | 否* | 邮箱，与 phone_number 二选一 |
| `target` | string | **是** | `ANY`（不限制）或 `USER`（账号必须存在） |
| `email_redirect_to` | string | 否 | Magic Link 跳转 URL，仅 email 模式 |

### 响应 200（验证码模式）
```json
{
  "verification_id": "your_verification_id",
  "expires_in": 600,
  "is_user": true
}
```

### 错误码
- `captcha_required` (4028) — 需要先完成图片验证码
- `invalid_phone_number` (4001) — 手机号格式错误
- `user_not_found` (4004) — 用户不存在（target=USER 时）
- `rate_limit_exceeded` (4029) — 发送频率过高，等 60s

**规则**: 验证码 6 位，有效期 600 秒。同一手机/邮箱 60 秒只能发一次。

---

## 2. 验证验证码 — POST /auth/v1/verification/verify

**文档**: https://docs.cloudbase.net/en/http-api/auth/auth-verify-verification

### 请求体 (JSON)
| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `verification_id` | string | **是** | 发送验证码返回的 ID |
| `verification_code` | string | **是** | 用户收到的 6 位验证码 |

### 响应 200
```json
{
  "verification_token": "your_verification_token",
  "expires_in": 600
}
```

### 错误响应
```json
{
  "error": "invalid_verification_code",
  "error_code": 4001,
  "error_description": "Verification code error, please re-enter"
}
```

---

## 3. 用户登录 — POST /auth/v1/signin

**文档**: https://docs.cloudbase.net/en/http-api/auth/auth-sign-in

### 请求头
| Header | 必填 | 说明 |
|---|---|---|
| `x-device-id` | 否 | 设备 ID |

### Query 参数
| 参数 | 必填 | 说明 |
|---|---|---|
| `client_id` | 否 | 默认为环境 ID |

### 请求体 (JSON，两种方式互斥)
| 字段 | 类型 | 说明 |
|---|---|---|
| `username` + `password` | string | 用户名密码登录 |
| `verification_token` | string | 验证码登录（从 verify 接口获取） |

### 响应 200
```json
{
  "token_type": "Bearer",
  "access_token": "...",
  "refresh_token": "m.aB3cD4...",
  "expires_in": 7200,
  "scope": "string",
  "sub": "9876543210123456789",
  "groups": ["string"]
}
```

### 错误码
- `invalid_username_or_password` (4043) — 用户名或密码错误
- `captcha_required` — 重试次数过多
- `password_not_set` — 用户未设置密码
- `invalid_status` — 账号锁定

---

## 4. 注册新用户 — POST /auth/v1/signup

**文档**: https://docs.cloudbase.net/en/http-api/auth/auth-sign-up

### 请求头
| Header | 必填 | 说明 |
|---|---|---|
| `x-device-id` | 否 | 设备 ID |

### 请求体 (JSON)
| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `phone_number` | string | 否* | 手机号注册 |
| `email` | string | 否* | 邮箱注册 |
| `verification_token` | string | **是** | 从 verify 接口获取 |
| `username` | string | 否 | 2-48 字符 |
| `password` | string | 否 | 可选密码 |
| `name` | string | 否 | 昵称 |
| `gender` | string | 否 | `MALE` 或 `FEMALE` |
| `picture` | string | 否 | 头像 URL |

⚠️ **不能仅用用户名+密码注册** — 必须通过手机/邮箱/第三方验证身份。

### 响应 200
同登录接口的 token 响应。

---

## 5. 刷新/获取 Token — POST /auth/v1/token

**文档**: https://docs.cloudbase.net/en/http-api/auth/auth-grant-token

支持 3 种 OAuth 2.0 grant_type:

### 模式 1: 刷新 Token
```json
{
  "grant_type": "refresh_token",
  "refresh_token": "m.pQ1rS2..."
}
```
返回新 access_token + 新 refresh_token（旧的立即失效）。

### 模式 2: 密码
```json
{
  "grant_type": "password",
  "username": "zhangsan",
  "password": "your-password"
}
```

### 模式 3: 客户端凭证（服务端管理员）
请求头: `Authorization: Basic ${base64(SecretId:SecretKey)}`
```json
{
  "grant_type": "client_credentials"
}
```
返回 access_token（无 refresh_token），超管权限，有效期 432000s（5 天）。

### 响应 200
```json
{
  "token_type": "Bearer",
  "access_token": "...",
  "refresh_token": "...",
  "expires_in": 7200,
  "sub": "9876543210123456789"
}
```

---

## 6. 获取当前用户信息 — GET /auth/v1/user/me

**文档**: https://docs.cloudbase.net/en/http-api/auth/user-me

### 请求头
| Header | 必填 |
|---|---|
| `Authorization: Bearer <access_token>` | **是** |

### 响应 200
```json
{
  "sub": "9876543210123456789",
  "name": "Zhang San",
  "picture": "https://example/avatar.jpg",
  "username": "zhangsan",
  "email": "zhangsan@example",
  "phone_number": "+86 13000000000",
  "providers": [
    {
      "id": "email",
      "provider_user_id": "zhangsan@example",
      "name": "Email Login"
    }
  ],
  "status": "ACTIVE",
  "gender": "MALE",
  "groups": [{"id": "user"}],
  "meta": {},
  "created_at": "2023-01-01T00:00:00Z",
  "updated_at": "2023-01-15T10:30:00Z",
  "user_id": "9876543210123456789",
  "has_password": true,
  "internal_user_type": "generalUser",
  "type": "external",
  "user_source": 1,
  "user_desc": "Regular User",
  "last_login": "2023-01-15T10:30:00Z"
}
```

**status 值**: `DEFAULT`, `ACTIVE`, `PENDING`, `BLOCKED`

---

## 7. 修改用户基本信息 — POST /auth/v1/user/basic/edit

**文档**: https://docs.cloudbase.net/en/http-api/auth/user-edit-user-basic-info

### 请求头
| Header | 必填 |
|---|---|
| `Authorization: Bearer <access_token>` | **是** |

### 请求体 (JSON)
| 字段 | 类型 | 说明 |
|---|---|---|
| `user_id` | string | 用户 ID（可选，修改其他用户需管理员权限） |
| `nickname` | string | 2-48 字符 |
| `username` | string | 2-48 字符 |
| `phone` | string | 格式 `+86 13000000000` |
| `email` | string | 有效邮箱 |
| `description` | string | 用户简介 |
| `avatar_url` | string | 头像 URL |
| `gender` | string | `MALE` 或 `FEMALE` |

### 响应 200
`{}`（空对象 = 成功）

### 错误响应
```json
{
  "error": "username_already_exists",
  "error_code": 409,
  "error_description": "Username already exists"
}
```

---

## 8. 用户登出 — POST /auth/v1/user/signout

**文档**: https://docs.cloudbase.net/en/http-api/auth/auth-sign-out

### 请求头
| Header | 必填 |
|---|---|
| `Authorization: Bearer <access_token>` | **是** |

### 请求体 (JSON)
| 字段 | 类型 | 说明 |
|---|---|---|
| `redirect_uri` | string | 登出后跳转 URL |
| `state` | string | 跳转参数 |

### 响应 200
```json
{
  "redirect_uri": "https://example/logout-callback?state=logout_state_123"
}
```

即使 access_token 已过期，登出仍返回成功。refresh_token 被标记为不可用。

---

## 附录：其他 Auth API

### 验证 Token — GET /auth/v1/token/introspect
**文档**: https://docs.cloudbase.net/en/http-api/auth/auth-token-introspect
返回 `{token_type, client_id, sub, scope}` 有效，`{}` 无效/过期。

### 匿名登录 — POST /auth/v1/signin/anonymously
**文档**: https://docs.cloudbase.net/en/http-api/auth/auth-sign-in-anonymously
Header `x-device-id` 必填。一个设备 = 一个匿名用户。`scope: "anonymous"`。

### 获取服务端 Token — POST /auth/v1/token/clientCredential
**文档**: https://docs.cloudbase.net/en/http-api/auth/auth-client-credential
服务端专用。`Authorization: Basic base64(SecretId:SecretKey)`。返回管理员 token，5 天有效，无 refresh_token。

---

## App 端点 → 文档映射

| App 端点 | 官方文档 |
|---|---|
| `auth/v1/verification` | https://docs.cloudbase.net/en/http-api/auth/auth-send-verification |
| `auth/v1/verification/verify` | https://docs.cloudbase.net/en/http-api/auth/auth-verify-verification |
| `auth/v1/signin` | https://docs.cloudbase.net/en/http-api/auth/auth-sign-in |
| `auth/v1/signup` | https://docs.cloudbase.net/en/http-api/auth/auth-sign-up |
| `auth/v1/token` | https://docs.cloudbase.net/en/http-api/auth/auth-grant-token |
| `auth/v1/user/me` | https://docs.cloudbase.net/en/http-api/auth/user-me |
| `auth/v1/user/basic/edit` | https://docs.cloudbase.net/en/http-api/auth/user-edit-user-basic-info |
| `auth/v1/user/signout` | https://docs.cloudbase.net/en/http-api/auth/auth-sign-out |
