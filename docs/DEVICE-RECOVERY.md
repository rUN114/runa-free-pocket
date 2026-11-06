# 设备中断恢复与只读验证

驱动仅依赖 Python 标准库与 adb。所有模式都要求明确设备和输出目录，不会自行选设备，不会清应用数据、缓存或 Cookie。输出可能包含本机账号面板文字，请保存在私密目录，不提交仓库。

## 实际 release 只读启动

先安装待验收的非调试 release APK，再执行：

```powershell
python tests/device/native-smoke.py --serial 127.0.0.1:16448 --output-dir D:/Android/verification/release --mode release-readonly
```

驱动检查 APK 不含 DEBUGGABLE、启动命令成功、前台为目标应用、目录含原生 catalogue 且无 WebView；只点击账号与领取记录两个面板入口并返回。它不点击领取、登录、切换账号、退出账号、继续任务或清理操作。应用正常启动本身可以刷新目录；这不是离线夹具，也不是完整真实订单验收。记录 `summary.json` 和每步命令、原生层级，失败立即停止。

## debug 独立恢复演练

构建并安装 debug APK 和匹配的 debug androidTest APK（构建参数 `-PrunaTestBuildType=debug`）。若覆盖同包名 release，必须使用相同签名；签名不匹配时不能卸载来绕过，因为卸载会删除真实数据。仅使用 MuMu VM2，不使用 VM0/VM1。确认安装没有清除已有数据后执行：

```powershell
python tests/device/native-smoke.py --serial 127.0.0.1:16448 --output-dir D:/Android/verification/recovery --mode debug-recovery
```

`recoveryPrepare` 在 debug 专用、非导出 `NativeRecoveryActivity` 中用真实 ClaimCoordinator 发起 Fab claim，以独立离线后台供应目录与库存，推进到claim后发送product actionReady；假transport收到唯一一次product许可时，立即检查独立prefs已持久化同run的预约及productAuthorized，再保留运行中批次，不做正常暂停。准备阶段许可仅计数，不执行任何网页脚本或网络订单。宿主没有 WebView、网络客户或生产 repository。唯一写入为 `native-recovery-diagnostic` 偏好。

Python 驱动检查 instrumentation 的 `verification=passed: recoveryPrepare` 和 `INSTRUMENTATION_CODE: -1`，拉取 JSON 后从 adb 外部 force-stop 目标包并确认 pid 消失。随后重新 instrumentation，宿主断言 PID 不同，重建账本已自动 paused，run ID 与预约完全相同。调用真实 ClaimCoordinator 的 resumeBatch，以独立内存后台供应 Fab 目录与库存。没有用户确认时，只执行 discover 和 syncFab，恢复阶段新增许可为 0，预约和 uncertain 状态保留。整个过程不评估网页脚本、不发送订单。

诊断前后对真实 catalogue、claim-journal、epic-auth XML 和已存在的 WebView Cookies 文件只读计算 SHA-256；跨进程也核对准备时摘要。摘要变化会失败，报告不导出真实内容。诊断不处理系统强杀之外的全部故障类型，不能代替真实订单或服务端幂等验收。

## 同步 commit 主线程成本

```powershell
python tests/device/native-smoke.py --serial 127.0.0.1:16448 --output-dir D:/Android/verification/benchmark --mode debug-benchmark
```

`commitBenchmark.json` 包含约 30 项、每项含预约的真实 ClaimJournal 序列化字节数；主线程 30 次全新诊断 prefs 文件的首次 commit，和 100 次同文件同 key 变更字节的重复 commit，另测20条历史、每条30项预约账本的20次变更字节commit，单独报告保留历史payload字节数、样本数和分位数。各组分别报告样本数、p50/p95/max 毫秒与总采样次数。所有写入名称均以 `native-commit-` 开头，绝不访问真实账本。commit分组计时覆盖 edit/commit，不包含构建账本与初次 getSharedPreferences。另在 serialization 字段单列30项批次与20条保留历史账本各20次 serialize() 的 p50/p95/max，只计 JSON 序列化，不含 UTF-8 编码或 commit；serialization.totalSamples=40，与顶层 totalSamples（commit采样次数）分开统计；首次文件 commit 不代表新进程冷启动或操作系统缓存冷态。保留这些诊断文件便于复查，驱动不会清理真实数据。模拟器结果仅适用于该虚拟设备与当时负载，不推断物理手机延迟。

## 浏览器诊断

```powershell
python tests/device/native-smoke.py --serial 127.0.0.1:16448 --output-dir D:/Android/verification/browser --mode debug-browser
```

保留原有 browserFixture（弹窗路由、nonce、子帧脚本）和 timeoutFixture（静默页面40秒提醒）；progressTimeoutFixture 在真实 setStatus 进度更新后等待40秒，要求同一原生状态同时保留进度及超时提醒。showFailureFixture 检查非法地址、外站、特殊端口和已关闭浏览器的 show 返回 false 且不创建 WebView；不调用会清理真实会话的 clearSession。accountDraftFixture 仅在假状态中打开账号页，将地区草稿CN改HK，经过busy与idle更新后仍应HK；外部已提交country变US时应更新为US，不保存真实设置。另有 networkErrorFixture，用官方 host 的不可用端口 1 触发网络错误，检查原生状态含 host 且不含路径和查询秘密。此模式使用隔离原生夹具和拦截页面，但 WebView 沿用应用既有 WebView 存储，不能将其描述为 Cookie 隔离；不会主动清理 Cookie，不执行登录或领取。错误脱敏断言仅覆盖原生 TextView，不覆盖 Chromium 内建错误页。

每个 instrumentation 模式均同时检查结束码、明确的 mode 成功文本和新拉取的 JSON；adb 返回 0 不等于测试通过。`commands.json` 保留每步命令和结果，`summary.json` 仅在全部断言完成后写入。开始新一轮时使用新的输出目录，避免混淆历史结果。

## 本次执行记录

设备实测结果以交付时的验证记录和本机输出 JSON 为准；本文提供可重复步骤，不把尚未执行的模式记为通过。

2026-11-01，MuMu VM2（127.0.0.1:16448）已实测独立恢复：外部 force-stop 后 PID 6536→6609，同一 run/预约保留，模式 discover→syncFab→check，许可为0，受保护真实存储摘要未变化。该记录只证明本地独立演练，不证明真实订单恢复。

同设备串行 benchmark-4：20,930字节单批次首次commit（30次）p50/p95/max=1.392/3.369/3.641ms，重复commit（100次）=3.837/4.888/6.180ms；20条历史×30项、425,048字节commit（20次）=8.111/10.534/11.091ms。独立serialize各20次：单批次=0.557/1.522/1.660ms，保留历史=10.223/11.841/13.371ms。150次commit与40次serialize分组测量，分位数不能相加作为端到端延迟；未测JSON编码到磁盘完成的整体时长。大账本的主线程成本需要保留为局限，不推断物理机表现。原始结果位于本机 `D:/Android/Builds/runa-native-review/verification/device-benchmark-4/commitBenchmark.json`。

旧实现的两个新回归用例已取得明确失败：progressTimeoutFixture 未在43秒内同时保留进度和超时提醒；accountDraftFixture 在busy更新后丢失HK草稿。失败结束码均为0（失败），说明驱动不能依赖adb进程exit code判断成功。修复后应以新一轮完整driver的summary为准。

修复后最终debug driver已通过：`device-browser-green-1/summary.json` 包含弹窗/nonce/子帧、静默超时、保留进度的超时、show失败、地区草稿及网络脱敏全部断言；每模式均明确成功结束且新拉取JSON通过。`device-recovery-green-1/summary.json`再次通过新生产代码的跨进程恢复，受保护真实存储摘要不变。未发送任何真实订单。


## 45秒慢加载覆盖回归

`--mode debug-slow` 单独执行约47秒的 slowProgressFixture，不加入常规debug-browser重复等待。夹具保留真实生产WebViewClient的onPageStarted/onPageFinished委托，只在shouldInterceptRequest中供应本机HTML流并延迟EOF；不伪造page.progress。等待页面启动至少46.5秒后，要求真实progress仍小于100，且原生状态仍同时含具体进度与40秒提醒；finally释放EOF并关闭浏览器。延迟流上限55秒，所有页面请求均由夹具拦截，不进行真实订单。
