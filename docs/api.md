# 图形服务 API

基础路径 `/api/graphics`；生产请求头 `X-Api-Key`。所有 JSON 返回统一格式：

```json
{"success":true,"code":"OK","message":"成功","data":{}}
```

## 任务接口

| 请求 | 用途 |
| --- | --- |
| `POST /tasks` | 提交或幂等查询任务，未完成返回 202，已完成返回 200 |
| `GET /tasks/{taskId}` | 查询状态与文件描述 |
| `GET /tasks/{taskId}/artifact` | 成功后下载文件流，PNG 或 SVG |
| `GET /capabilities` | 操作枚举、资源限制、实际字体和工作线程情况 |

`taskId` 就是请求的 `requestId`，必须匹配 `[A-Za-z0-9][A-Za-z0-9_-]{0,79}`。只提交 `raster` 或 `svg` 中的一个。

状态为 `QUEUED → RUNNING → SUCCEEDED / FAILED / TIMED_OUT`，重启中断的任务转为 `FAILED`。客户端每隔 0.5–2 秒查询一次，设置整体截止时间；不要在 MES 的 Web 请求线程中无限等待。重复提交时必须保持参数一致；幂等保留期与任务保留期相同。

成功结果：

```json
{
  "taskId":"demo-label-001",
  "status":"SUCCEEDED",
  "createdAt":1794700000000,
  "updatedAt":1794700001000,
  "artifact":{
    "filename":"result.png",
    "mediaType":"image/png",
    "bytes":1234,
    "sha256":"文件 SHA-256",
    "width":591,
    "height":119,
    "unit":"px",
    "downloadPath":"/api/graphics/tasks/demo-label-001/artifact",
    "objectName":null
  },
  "errorCode":null,
  "message":null
}
```

时间为 Unix 毫秒，PNG 宽高单位为像素，SVG 为 MES 使用的毫米坐标。`objectName` 仅在启用 OSS 时返回。下载地址是服务自身的相对路径，鉴权方式相同；建议 MES 使用 OSS 对象名接续现有业务。

## raster 参数

| 字段 | 说明 |
| --- | --- |
| `kind` | 必填：SOLID、BORDER、LABEL、STRIPES、CIRCLE、CROSS |
| `widthMm` / `heightMm` | 必填，正数；BORDER 指包含留白后的整体画布 |
| `dpi` | 必填，1–600，像素为 `ceil(mm / 25.4 × dpi)` |
| `foreground` | 默认 `#000000` |
| `background` | 默认 `transparent`；颜色只接受 #RRGGBB 或 transparent |
| `strokeMm` | 描边宽度；0 表示最小 1px |
| `lines` | LABEL 必填；最多 32 行，每行最多 2048 字符 |
| `fontFamily` / `fontSizeMm` / `bold` | 默认 SansSerif、自动大小、false；文字超出画布会缩小 |
| `stripeMm` / `verticalStripes` | 条带宽度，0 表示 1px；默认横条，交替使用前景色与白色 |
| `rotationDegrees` | -360 到 360，默认 0，顺时针旋转；输出画布随之扩大 |
| `margins` | BORDER 的 left/top/right/bottom 内框边距，默认 0 |
| `dashedInsetMm` | BORDER 内缩虚线边框距离，默认不绘制 |

边框、标签、条纹示例见 `examples/`。需要固定生产视觉效果时应显式指定 DPI、字体、字号和颜色。

## svg 参数

| 字段 | 说明 |
| --- | --- |
| `kind` | 必填：MASK 或 EDIT |
| `width` / `height` | MASK 必填，生成矩形路径蒙版 |
| `sourceImage` / `requirePlt` | MASK 原图引用及切割元数据 |
| `source` | EDIT 必填，内联 SVG 文本，不传 URL |
| `margins` | left/top/right/bottom 外扩量；原内容平移，新增标记按新画布坐标定位 |
| `normalizeRects` | 将无 rx/ry 的 rect 转成 path；保留圆角矩形 |
| `removeGroupIds` | 精确按 id 删除 g 分组，不做字符串模糊匹配 |
| `imageReplacements` | `{分组id: 新图片引用}`，更新目标的 img 元数据 |
| `marks` | 新增定位标记列表，最多 1000 个 |

`marks` 元素字段：kind（RECT/CIRCLE/CROSS）、唯一 id、x/y（左上角）、width/height、image（可选）、requirePlt、rotationDegrees（围绕标记中心）。保留 img、require-plt、data-forme、data-rotation 元数据。

EDIT 只接收标准 SVG 命名空间，根节点必须有数值宽高（可带 mm/px 后缀，按 MES 数值坐标解释）；如有 viewBox，必须为 `0 0 width height`。不执行浏览器 px→mm 换算，不支持百分比、任意 CSS 或不一致的 viewBox。请先归一化通用设计软件导出的 SVG。

支持 svg、g、path、rect、circle、ellipse、line、polyline、polygon、defs、clipPath、title、desc、text、tspan、image，以及常用几何、样式属性和 MES 元数据。拒绝脚本、事件属性、style、DOCTYPE、外部实体及外部 CSS 表达式。引用仅允许对象名或 HTTP(S) URL，服务不会下载或执行引用资源。

编辑顺序：删除分组/替换引用/矩形转路径 → 扩边并平移原内容 → 在新画布插入标记。

## 错误与重试

| HTTP / 错误码 | 客户端处理 |
| --- | --- |
| 400 INVALID_REQUEST | 修正尺寸、JSON 或 SVG |
| 401 UNAUTHORIZED | 检查服务端 API Key 配置 |
| 404 TASK_NOT_FOUND / ARTIFACT_EXPIRED | 查询保留期；必要时新编号重新生成 |
| 409 IDEMPOTENCY_CONFLICT | 相同编号已绑定不同参数；使用新编号 |
| 409 ARTIFACT_NOT_READY | 等待成功或检查失败原因 |
| 413 REQUEST_TOO_LARGE | 减小内联 SVG/请求体 |
| 429 QUEUE_FULL | 根据 Retry-After 退避，同编号重试 |
| 503 STORAGE_FULL | 清理/扩容本地存储后重试 |

异步失败在查询结果中返回 `errorCode`，例如 INVALID_GRAPHICS、RENDER_FAILED、TASK_TIMEOUT、SERVICE_RESTARTED；查询本身仍返回 200。网络不确定时先用原编号查询或重发，避免重复生成。

## MES Java 调用片段

继续使用 MES 已有的 RestTemplate；调用放入现有后台任务流程。配置连接及读取超时，API Key 从 MES 配置读取。

```java
HttpHeaders headers = new HttpHeaders();
headers.setContentType(MediaType.APPLICATION_JSON);
headers.set("X-Api-Key", graphicsApiKey);

Map<String, Object> body = Map.of(
    "requestId", "label-" + UUID.randomUUID(),
    "raster", Map.of("kind", "LABEL", "widthMm", 100,
        "heightMm", 20, "dpi", 150, "fontFamily", "Noto Sans CJK SC",
        "lines", List.of("生产标签", "订单 MES-001")));

ResponseEntity<String> accepted = restTemplate.postForEntity(
    graphicsBaseUrl + "/api/graphics/tasks", new HttpEntity<>(body, headers), String.class);
// 从响应 data.taskId 保存任务编号；后台定时查询，不在 HTTP 请求中无限等待。
ResponseEntity<String> state = restTemplate.exchange(
    graphicsBaseUrl + "/api/graphics/tasks/" + body.get("requestId"),
    HttpMethod.GET, new HttpEntity<Void>(headers), String.class);
// 解析 data.status；成功后将 artifact.objectName 或下载后的对象名写回业务对象。
```
