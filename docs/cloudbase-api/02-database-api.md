# CloudBase v1 Data Model API — 数据模型接口完整文档

**OpenAPI Spec**: https://docs.cloudbase.net/openapi/en/datasource.v1.openapi.yaml

**认证**: `Authorization: Bearer <access_token>`

⚠️ Data Model API 不支持腾讯云签名 V3 认证。

**HTTP 状态码**: 只有 `2xx`。业务错误在响应体中返回。

---

## 通用错误码

| 错误码 | 说明 |
|---|---|
| `AUTH_FAILURE` | 无权限 |
| `FAILED_OPERATION` | 操作失败 |
| `OPERATION_TIMEOUT` | 操作超时 |
| `INVALID_PARAMETER` | 参数无效 |
| `MISSING_PARAMETER` | 缺少参数 |
| `LIMIT_EXCEEDED` | 超出配额 |
| `RESOURCE_NOT_FOUND` | 资源不存在 |
| `DATA_SOURCE_OP_AUTH_FAILURE` | 数据源鉴权失败 |
| `FLEXDB_INTERNAL_ERROR` | 云数据库请求错误 |
| `FLEXDB_REQUEST_TIMEOUT` | MongoDB 超时 |
| `AUTH_FAILURE_TOKEN_FAILURE` | Token 错误 |
| `REQUEST_LIMIT_EXCEEDED` | 频率限制 |
| `FLEXDB_READ_LIMIT` | 数据库读超限 |
| `FLEXDB_WRITE_LIMIT` | 数据库写超限 |
| `DATASOURCE_NOT_EXIST` | 数据源不存在 |
| `TABLE_NOT_EXIST` | 表不存在 |

---

## 1. 创建单条记录 — POST /v1/model/prod/:modelName/create

**文档**: https://docs.cloudbase.net/en/http-api/model/create-item

### 请求体
```json
{
  "data": {
    "field1": "value1",
    "field2": 123
  }
}
```

⚠️ 必须用 `data` 字段包裹数据。

### 响应 200（成功）
```json
{
  "data": {
    "id": "created-record-id"
  },
  "requestId": "unique-request-id"
}
```

### 响应（错误）
```json
{
  "code": "AUTH_FAILURE",
  "message": "No permission",
  "requestId": "unique-request-id"
}
```

---

## 2. 查询多条记录（带过滤） — POST /v1/model/prod/:modelName/list

**文档**: https://docs.cloudbase.net/en/http-api/model/get-records

### 请求体
```json
{
  "filter": {
    "where": {
      "owner": { "$eq": "user123" }
    }
  },
  "select": { "$master": true },
  "pageSize": 100,
  "pageNumber": 1,
  "getCount": true,
  "orderBy": [
    { "createdAt": "desc" }
  ]
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `filter` | object | 否 | 过滤条件，含 `where` 子句 |
| `select` | object | 否 | 字段选择，`{ "$master": true }` 查全部 |
| `pageSize` | integer | 否 | 默认 10 |
| `pageNumber` | integer | 否 | 默认 1 |
| `getCount` | boolean | 否 | 是否返回总数，默认 false |
| `orderBy` | array | 否 | 排序，最多 3 个字段，值 `"asc"`/`"desc"` |

### 响应 200
```json
{
  "data": {
    "records": [
      { "_id": "abc", "owner": "user123", "createdAt": 1717488585078 }
    ],
    "total": 42
  },
  "requestId": "unique-request-id"
}
```

`total` 仅在 `getCount=true` 时返回。

### Filter 查询操作符
参考: https://docs.cloudbase.net/lowcode/api/datasource-v2#query-parameter-description

| 操作符 | 说明 |
|---|---|
| `$eq` | 等于 |
| `$ne` | 不等于 |
| `$gt` | 大于 |
| `$gte` | 大于等于 |
| `$lt` | 小于 |
| `$lte` | 小于等于 |
| `$in` | 在数组中 |
| `$nin` | 不在数组中 |
| `$like` | 模糊匹配 |
| `$nlike` | 不模糊匹配 |
| `$regex` | 正则匹配 |

---

## 3. 简单查询（无过滤） — GET /v1/model/prod/:modelName/list

**文档**: https://docs.cloudbase.net/en/http-api/model/get-records-easy

### Query 参数
| 参数 | 类型 | 说明 |
|---|---|---|
| `pageSize` | integer | 页大小 |
| `pageNumber` | integer | 页码，默认 1 |
| `getCount` | boolean | 是否返回总数 |

响应同 POST /list。

---

## 4. 更新单条记录（按过滤条件） — PUT /v1/model/prod/:modelName/update

**文档**: https://docs.cloudbase.net/en/http-api/model/update-item

### 请求体
```json
{
  "filter": {
    "where": {
      "_id": { "$eq": "record-id" }
    }
  },
  "data": {
    "title": "Updated Title",
    "updatedAt": 1717488585078
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `filter` | object | **是** | 过滤条件 |
| `data` | object | **是** | 要更新的字段 |

### 响应 200
```json
{
  "data": {
    "count": 1
  },
  "requestId": "unique-request-id"
}
```

`count`: 受影响的记录数。非零 = 成功。

---

## 5. 创建或更新记录（Upsert） — POST /v1/model/prod/:modelName/upsert

**文档**: https://docs.cloudbase.net/en/http-api/model/upsert-item

### 请求体
```json
{
  "filter": {
    "where": {
      "_id": { "$eq": "record-id" }
    }
  },
  "create": {
    "_id": "record-id",
    "title": "Hello",
    "body": "World"
  },
  "update": {
    "body": "Updated World"
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `filter` | object | **是** | 查重条件 |
| `create` | object | **是** | 记录不存在时使用的数据 |
| `update` | object | **是** | 记录已存在时更新的数据 |

### 响应 200
```json
{
  "data": {
    "count": 0,
    "id": "record-id"
  },
  "requestId": "unique-request-id"
}
```

- `count` 非零 → 记录已存在，执行了更新
- `id` 非空 → 记录不存在，创建了新记录

---

## 6. 按 ID 删除记录 — DELETE /v1/model/prod/:modelName/:recordId/delete

**文档**: https://docs.cloudbase.net/en/http-api/model/delete-item-by-id

### 路径参数
| 参数 | 说明 |
|---|---|
| `modelName` | 数据模型标识 |
| `recordId` | 记录 ID |

### 响应 200
```json
{
  "data": {
    "count": 1
  },
  "requestId": "unique-request-id"
}
```

---

## 7. 按条件删除记录 — POST /v1/model/prod/:modelName/delete

**文档**: https://docs.cloudbase.net/en/http-api/model/delete-item

### 请求体
```json
{
  "filter": {
    "where": {
      "owner": { "$eq": "user123" }
    }
  }
}
```

### 响应 200
```json
{
  "data": {
    "count": 1
  },
  "requestId": "unique-request-id"
}
```

---

## 8. 按 ID 获取单条记录 — GET /v1/model/prod/:modelName/:recordId/get

**文档**: https://docs.cloudbase.net/en/http-api/model/get-item-by-id

### 响应 200
```json
{
  "data": {
    "record": {
      "_id": "abc",
      "field1": "value1"
    }
  },
  "requestId": "unique-request-id"
}
```

---

## 9. 按条件获取单条记录 — POST /v1/model/prod/:modelName/get

**文档**: https://docs.cloudbase.net/en/http-api/model/get-item

### 请求体
```json
{
  "filter": {
    "where": {
      "owner": { "$eq": "user123" }
    }
  },
  "select": { "$master": true }
}
```

响应同按 ID 获取。

---

## 响应 Schema 汇总

| Schema | 关键字段 |
|---|---|
| `CreateResponse` | `data.id` (string) |
| `FindResponse` | `data.record` (object) |
| `FindManyResponse` | `data.records` (array), `data.total` (int) |
| `UpdateDeleteResponse` | `data.count` (int) |
| `UpsertResponse` | `data.count` (int), `data.id` (string) |
| `ErrorResponse` | `code` (string), `message` (string), `requestId` (string) |

---

## App 端点 → 文档映射

| App 端点 | HTTP 方法 | 官方文档 |
|---|---|---|
| `v1/model/prod/{modelName}/create` | POST | [create-item](https://docs.cloudbase.net/en/http-api/model/create-item) |
| `v1/model/prod/{modelName}/list` | POST | [get-records](https://docs.cloudbase.net/en/http-api/model/get-records) |
| `v1/model/prod/{modelName}/update` | PUT | [update-item](https://docs.cloudbase.net/en/http-api/model/update-item) |
| `v1/model/prod/{modelName}/upsert` | POST | [upsert-item](https://docs.cloudbase.net/en/http-api/model/upsert-item) |
| `v1/model/prod/{modelName}/{recordId}/delete` | DELETE | [delete-item-by-id](https://docs.cloudbase.net/en/http-api/model/delete-item-by-id) |
| `v1/model/prod/{modelName}/delete` | POST | [delete-item](https://docs.cloudbase.net/en/http-api/model/delete-item) |
