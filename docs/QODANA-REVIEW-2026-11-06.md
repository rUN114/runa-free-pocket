# Qodana 诊断复核

原报告来自 [PR 1 的扫描](https://github.com/rUN114/runa-free-pocket/actions/runs/42171567948)，包含 1 条错误、38 条警告、4 条提示。扫描成功执行与没有诊断是两件事。旧配置没有设置失败阈值，错误没有使工作流失败。

## 本次处理

- 启动图标在基础 mipmap 目录中使用 bitmap 根标签，违反资源类型约定。项目 minSdk 为 26，将原 v26 目录中的有效 adaptive-icon 定义合并到基础目录。图案、颜色及 inset 保持原值。
- WebView 的功能检查原在 MainActivity 中，静态检查没有跟踪到全部 API。现在实际调用处使用正向检查，失败通过现有回调报告，并停止旧请求。
- 图片 XML 有加载中的描述，绑定后仍由加载器写入对应商品名称。列表分节标题使用 ViewCompat，支持 Android 8 和 8.1。
- 异步偏好写入使用 KTX，延时使用显式秒单位，整理 URI、冗余正则分组和诊断 JSON 类型。下单前、账号清理标记和诊断的同步 commit 及返回结果保留。
- 配置 failureConditions.severityThresholds.critical 为 0。依照 [Qodana 严重程度映射](https://www.jetbrains.com/help/qodana/ui-overview.html#Severity+levels)，ERROR 对应 Critical；[门禁](https://www.jetbrains.com/help/qodana/quality-gate.html)超限会使工作流失败。没有排除检查项或引入基线隐藏诊断。

## 保留项及依据

| 原报告类别 | 数量 | 处理依据 |
| --- | --- | --- |
| 依赖、SDK、AGP 有新版 | 10 | 这是版本更新提示。整组升级会涉及构建、返回导航和目标 SDK 行为，作为独立兼容性升级处理。 |
| 剩余 KTX 建议 | 5 | 都是需要同步 commit 或其布尔返回值的持久化路径。 |
| 重复绘制 | 4 | 这些 XML 作为子视图使用，Artwork 与下拉选项等有独立背景用途，不能仅按推断的 Activity 主题删除。 |
| 开启 JavaScript | 1 | 官方登录与结算需要脚本；继续使用官方地址限制、来源、nonce 和当前页面验证。 |
| JSONObject.NULL 身份比较 | 1 | 需要识别 JSON 哨兵，保留身份判断；其 equals 行为不能代替此契约。 |
| 多美元字符串建议 | 1 | 现有转义正确，保留可读的标签类名。 |

本次目标是处理有依据的问题和等价的写法建议。保留项仍由工具继续报告。

## 验证边界

本地 JavaScript、JVM、Python 回归和正式签名构建通过。Lint 从 22 条警告降至 9 条，错误为 0。模拟器本次启动未成功，未将先前版本的界面或真实订单验证当成本版验收。云端最终诊断数量以本 PR 对当前提交的扫描为准。
