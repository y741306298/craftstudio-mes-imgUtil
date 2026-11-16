# CraftStudio MES 图形服务

独立承载 MES 的 PNG 绘制、文字标签、条纹与定位标记、SVG 蒙版及图形编辑，隔离绘图的 CPU 和内存开销。

与现有 MES 保持 **Java 22 / Spring Boot 4.0.1 / Maven 四层模块**：

```text
craftstudio-imgutil-interfaces   HTTP 接口、鉴权、请求体限制、启动配置
            ↓
craftstudio-imgutil-application  任务编排、幂等、有界队列、超时及清理
            ↓
craftstudio-imgutil-infra        AWT/ImageIO、SVG DOM、文件存储、OSS
            ↓
craftstudio-imgutil-domain       图形参数、任务模型、仓储与渲染端口
```

沿用 MES 的模块依赖方向；不引入与绘图无关的数据库、Redis、RocketMQ 和私有 shared 依赖。无需访问 MES 数据库即可启动。

## 启动

需要 JDK 22 和 Maven 3.9+：

```bash
mvn -B -ntp verify
java -Xms128m -Xmx512m -XX:MaxDirectMemorySize=64m \
  -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true \
  -jar craftstudio-imgutil-interfaces/target/craftstudio-imgutil-interfaces-0.0.1-SNAPSHOT.jar
```

默认监听 `127.0.0.1:8092`，输出到 `./data`，开发模式允许不配置 API Key。对外服务须使用 `prod` 并设置 `GRAPHICS_API_KEY`；容器启动默认采用生产模式。

```bash
cp .env.example .env
# 编辑 .env，设置随机 GRAPHICS_API_KEY；不要将 .env 提交到仓库
docker compose up -d --build
```

容器默认限制 2 CPU / 768 MiB 内存，Java 最大堆 512 MiB，使用独立数据卷。默认仅绑定宿主机回环地址，MES 跨机器访问时请配置内网入口和 TLS。镜像安装 Noto CJK 字体，中文标签可传 `fontFamily: "Noto Sans CJK SC"`；裸机运行请安装相应字体。`GET /api/graphics/capabilities` 可查询实际可用字体。

## 已实现的操作

| 类别 | 操作 | 功能 |
| --- | --- | --- |
| PNG | `SOLID` | 纯色图、透明底 |
| PNG | `BORDER` | 外框、原图内框、内缩虚线框 |
| 标签 | `LABEL` | 多行文字、居中、自动缩小、粗体、字体选择 |
| 标记 | `STRIPES` | 横/竖黑白或指定深色条纹 |
| 标记 | `CIRCLE` / `CROSS` | 圆形定位点、十字定位标记 |
| SVG | `MASK` | 矩形路径蒙版、原图引用、MES 元数据 |
| SVG | `EDIT` | 画布扩边与内容平移、插入标记、删除分组、替换图片引用、矩形转路径 |

所有 PNG 操作支持顺时针任意角度旋转，宽高必须为正数；不再按单边尺寸、总像素或累计画布像素拒绝绘图。SVG 不会下载图片、执行脚本或进行栅格化。保留 `img`、`require-plt`、`data-*` 等 MES 元数据。生产工艺选择、订单字段拼装和数据库更新继续由 MES 负责。

服务已增加 `mes` 兼容操作，承载圆形/十字二维码标签、双面裱贴、裁赋开背、留白、多色工艺标签、拼接标记及热升华 SVG 规则。MES 对应改动通过持久化后台工作流调用这些操作，具体协议、上线顺序和字体要求见 [MES 异步接入文档](docs/mes-async-api.md)。

拼接零件使用 `SPLICE_PIECE`：PNG 条纹、文字、旋转、全部 SVG 标记和最终合并上传在同一任务完成，一次回调返回蒙版与8个素材对象名，capabilities 返回 `splicePieceVersion=1`。

MES 调用现已统一到 `AlgorithmCoreApiService`：POST 携带 `callbackConfig`，HTTP 202 后由图形服务主动回调最终结果，不再轮询任务状态。回调包含 HMAC-SHA256 签名，投递失败持久化退避重试，最多10次。`callbackProtocolVersion=1` 可在 capabilities 查询；未配置回调的手动任务仍可通过 GET 查询。

## 接口和示例

所有 `/api/**` 接口使用 `X-Api-Key` 请求头（启用鉴权时）。健康检查为 `GET /actuator/health`。

```bash
curl -X POST http://127.0.0.1:8092/api/graphics/tasks \
  -H "X-Api-Key: $GRAPHICS_API_KEY" \
  -H 'Content-Type: application/json' \
  --data-binary @examples/label.json

curl http://127.0.0.1:8092/api/graphics/tasks/demo-label-001 \
  -H "X-Api-Key: $GRAPHICS_API_KEY"

# status=SUCCEEDED 后下载；启用 OSS 时结果也包含 objectName
curl -o label.png http://127.0.0.1:8092/api/graphics/tasks/demo-label-001/artifact \
  -H "X-Api-Key: $GRAPHICS_API_KEY"
```

提交返回 `202`，重复提交已完成任务返回 `200`。同一个 `requestId` 和相同参数复用结果；相同编号但不同参数返回 `409`。失败任务重试使用新的编号。文件流不放入 JSON 或 Base64 中。

详细字段、错误码和 MES Java 调用示例见 [API 文档](docs/api.md)。

## 资源与运行边界

| 控制项 | 默认值 |
| --- | --- |
| 绘图工作线程 / 等待队列 | 2 / 16 |
| 输入、旋转及累计画布像素 | 无应用层上限 |
| DPI | 600 |
| 请求体 / 输出文件 | 1 MiB / 32 MiB |
| SVG 节点 / 嵌套深度 | 20,000 / 64 |
| 单任务执行超时 | 30 秒，不含排队时间 |
| 本地文件及任务保留 | 24 小时，每 10 分钟清理 |
| 本地容量准入阈值 | 2 GiB，另外预留并发输出空间 |

队列满返回 `429`，本地容量不足返回 `503`，均包含 `Retry-After`。请求大小、文件大小和队列等限制继续生效。旧 max-dimension/max-pixels 配置仅兼容绑定，不再参与判断，可从部署配置中删除。capabilities 返回 pixelLimitsEnabled=false，不再返回 maxDimension/maxPixels。

超时会标记 `TIMED_OUT` 并中断工作线程，但 Java AWT、PNG 编码和部分 I/O 不保证立即响应中断；实际工作退出前始终占用原并发槽位，不会因超时不断创建新线程。**最终资源隔离依靠独立 JVM、容器 CPU/内存限额及 OOM 自动退出重启**。首版不提供单任务子进程强制终止。

任务状态持久化为原子写入的 JSON，图片通过文件流输出；已完成任务重启后可继续查询。重启时未完成任务标记为 `FAILED / SERVICE_RESTARTED`，由 MES 使用新编号重试。队列尚未使用外部 MQ，不保证自动恢复执行。

当前是**单实例文件存储方案**，数据目录有进程锁。同一目录不可运行多个实例；横向扩容前应替换任务仓储及队列，或在 MES 侧明确分片路由。请给数据卷设置宿主机配额，容量准入检查不是操作系统硬配额。

## OSS

设置 `OSS_ENABLED=true`、`OSS_ENDPOINT`、`OSS_BUCKET`、`OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET` 后，生成文件同时上传 OSS。凭据只从服务端环境读取，不由 MES 请求传入。

返回 `artifact.objectName`，MES 可按自己的 OSS 权限读取。API 下载地址为相对路径，不包含签名 URL。自动清理只删除本地缓存和任务状态，**不会删除 OSS 对象**；请按业务需要配置 OSS 生命周期。不要在任务结果过期后复用旧编号覆盖业务仍在使用的 OSS 对象。

## 验证

`mvn verify` 包含像素与透明度验证、旋转尺寸、文字实际绘制、SVG 编辑及元数据、XML 实体与脚本拒绝、队列满载、幂等冲突、超时槽位、重启恢复、文件清理、API 鉴权与请求大小、任务提交到下载的集成测试。

CI 使用与 MES 相同的 JDK 22。部署前应使用真实中文字体、生产标签及大图样本做视觉验收和容量压测。
