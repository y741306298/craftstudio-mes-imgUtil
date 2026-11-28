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
| `GET /tasks/{taskId}/callback` | 查看回调投递状态、次数、下一次时间及错误 |
| `GET /capabilities` | 操作枚举、资源限制、实际字体和工作线程情况 |

`taskId` 就是请求的 `requestId`，必须匹配 `[A-Za-z0-9][A-Za-z0-9_-]{0,79}`。只提交 `raster`、`svg`、`mes` 中的一个。MES 专用生产图形请使用 [mes 兼容协议](mes-async-api.md)。

拼接零件的 PNG 素材、旋转和全部 SVG 标记使用一次 `SPLICE_PIECE` 任务完成，成功回调的 artifact.assets 返回8个素材对象名，capabilities 中 `splicePieceVersion=1` 表示支持。参数示例及上线顺序见 [MES 异步接口文档](mes-async-api.md)。

生产 MES 使用主动回调：在同一请求增加 `callbackConfig: {callbackUrl, callbackCustomValue}`。图形服务完成后签名 POST 最终结果；成功或失败都回调，投递失败按 5 秒、10 秒、30 秒、1 分钟、5 分钟的间隔最多重试 5 次并持久化状态。MES 回调格式和验签规则见 [异步接口文档](mes-async-api.md)。以下 GET 查询说明用于人工排障或未配置回调的独立调用方。

状态为 `QUEUED → RUNNING → SUCCEEDED / FAILED / TIMED_OUT`。服务先持久化请求，再返回 `HTTP 202 / QUEUED`。重启后继续处理包含请求体的排队任务，重启中断的执行中任务转为 `FAILED`。未配置回调的客户端可每隔 0.5–2 秒查询一次并设置整体截止时间。重复提交时必须保持参数一致，幂等保留期与任务保留期相同。

成功结果：

```json
{
  "taskId":"demo-label-001",
  "status":"SUCCEEDED",
  "createdAt":1794700000000,
  "updatedAt":1794700001000,
  "artifact":{
    "filename":"27ec4117-fca2-4de6-8ec5-ba39d7568084.png",
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

新生成的 PNG、SVG 及拼接素材均各自使用 UUID 文件名。使用返回的 `downloadPath` 或 `objectName` 获取文件。相同任务的幂等提交返回原文件。

画布单边尺寸、总像素及累计画布像素校验已移除，PNG 绘制、解码和旋转均不再受 max-dimension/max-pixels 限制；capabilities 返回 pixelLimitsEnabled=false。宽高正数、有限数值、DPI、文件大小和任务队列等校验继续生效。

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
| `fontFamily` / `fontSizeMm` / `bold` | 默认 Source Han Sans SC VF、自动大小、false。优先使用指定字体，缺字时选择可完整显示文字的字体。文字超出画布会缩小 |
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
| 400 INVALID_REQUEST | 修正非正数/非法尺寸、JSON 或 SVG |
| 401 UNAUTHORIZED | 检查服务端 API Key 配置 |
| 404 TASK_NOT_FOUND / ARTIFACT_EXPIRED | 查询保留期；必要时新编号重新生成 |
| 409 IDEMPOTENCY_CONFLICT | 相同编号已绑定不同参数；使用新编号 |
| 409 ARTIFACT_NOT_READY | 等待成功或检查失败原因 |
| 413 REQUEST_TOO_LARGE | 减小内联 SVG/请求体 |
| 503 SERVICE_STOPPING | 服务恢复后使用同一编号重试 |

异步失败在查询结果中返回 `errorCode`，例如 INVALID_GRAPHICS、RENDER_FAILED、TASK_TIMEOUT、SERVICE_RESTARTED。查询本身仍返回 200。网络不确定时先用原编号查询或重发，避免重复生成。

DOUBLE_TAG 的 qrDataUri 可省略。双面对裱固定箭头由 MES 作为独立 mark 提交；旧版 MES 将固定箭头 URL 误放到二维码位时兼容忽略该占位值，保持原布局。图形服务不会下载这些 URL；实际二维码仍使用 qr-content 或 PNG data URI。

## MES Java 调用片段

现有 MES 使用 `ImgUtilClient → AlgorithmCoreApiService.generateGraphicsAsync`，沿用算法调用记录和 callbackConfig。以下是统一提交的示意；实际由 ImgUtilClient 先注册持久化回调关联、生成确定的 requestId，再提交：

```java
GraphicsGenerationRequest request = new GraphicsGenerationRequest();
request.setRequestId(taskId);
request.setMes(Map.of("operation", "BLACK", "parameters", Map.of("width", 8, "height", 3)));

GraphicsCallbackCustomValue custom = new GraphicsCallbackCustomValue();
custom.setId(workflowId);
custom.setWorkflowId(workflowId);
custom.setTaskId(taskId);
CallbackConfig callback = new CallbackConfig();
callback.setCallbackUrl(graphicsCallbackUrl);
callback.setCallbackCustomValue(custom);
request.setCallbackConfig(callback);

algorithmCoreApiService.generateGraphicsAsync(request);
// HTTP 202 仅表示接收。最终结果 POST 到 MES generate_graphics 回调接口。
// 回调验签、持久化及唤醒后，由原后台工作流继续写入业务对象。
```

`external.api.generateGraphicsUrl` 为完整提交地址，`external.callbackApi.generate_graphics` 为图形服务可访问的 MES 回调地址；MES 与图形服务配置相同 API Key。完整配置和 HMAC-SHA256 验签规则见 [MES 异步接口文档](mes-async-api.md)。
