# MES 图形兼容迁移

已从 MES 主分支 d949c7e1 提取专用绘图核函数，在图形服务执行；MES 保留工艺和业务编排。

完整协议、对应操作、异步状态和部署说明见 [MES 异步接口文档](mes-async-api.md)。

| MES 原实现 | 新服务能力 | 迁移注意点 |
| --- | --- | --- |
| 各 LayoutBuildService.createBlackPng | raster SOLID | 显式指定物理尺寸和 DPI；原像素参数先换算 |
| SublimationSvg.borderPng | raster BORDER | MES 原方法使用 36 DPI 并限制 4096px；接入时保留原缩放及 2mm 描边参数 |
| AbstractFixedLiubaiProcessStrategy.createBorderPng | raster BORDER + margins/dashedInsetMm | 工艺决定是否绘制哪圈边框；需要更复杂边框时扩展参数，不能直接删除原策略 |
| createHorizontalLiubaiTagPng/createTextImage | raster LABEL | 确认原字号、文字边距与旋转方向；当前通用标签居中并自动缩小 |
| createStripeImage/createAlternatingStripePng | raster STRIPES | 以 1px 为带宽的旧代码可传 stripeMm=0 |
| SuperWidthSpliceMarkService 图像旋转 | raster rotationDegrees | 已支持旋转后画布上限和 PNG 输出 |
| generateAndUploadPathMaskSvg | svg MASK | MES 先确定矩形尺寸，服务生成并保存文件 |
| SVG 外扩/分组平移 | svg EDIT + margins | 扩边使用毫米坐标；输出同步更新 width/height/viewBox |
| SVG 插入点位/拼接标记 | svg EDIT + marks | MES 继续计算工艺点位；通用 RECT/CIRCLE/CROSS 由服务构建 |
| SVG 删除分组、替换 img、rect 转 path | svg EDIT | 删除只匹配精确 id；圆角矩形保留，已有元数据保留 |

CircleQr/CrossQr/DoubleSideMounting 的复合标签现由专用兼容操作执行，保留原富文本和布局函数。工艺点位规划、不同设备的版面业务选择继续位于 MES；刀路/PLT 仍使用既有独立算法。通用 LABEL 不替代专用标签。

推荐 MES 后续在 application 层新增 GraphicsService 调用适配器，参数仅使用纯绘图数据。后台任务保存 requestId，定期查询结果。遇到 429/503 退避；提交结果不确定时复用原编号。失败后的业务重试使用新编号。

先迁移纯色图和简单条纹，再迁移标签与边框，最后迁移 SVG 组合。每一类用实际生产样本比较尺寸、DPI、透明度、文字字体、旋转方向和 require-plt/img 元数据后，再切换对应 MES 调用点。

本地缓存保留 24 小时，不应被业务当成永久图片地址。建议生产环境开启 OSS，将 objectName 写入 MES；配置与生产文件保留策略一致的 OSS 生命周期。
