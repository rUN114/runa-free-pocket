# runa周免口袋 1.0.0

在安卓手机上查看 Epic 电脑游戏、Android 游戏和 Fab 限免素材，同步拥有状态并批量领取。最低 Android 8.0，运行不需要电脑或自建服务器。

[下载正式版 APK](https://github.com/rUN114/runa-free-pocket/releases/latest) · [版本记录](CHANGELOG.md) · [测试说明](docs/TESTING.md) · [依赖与许可证](THIRD_PARTY_NOTICES.md)

安装包 **331,441 字节，约 324 KiB**。三类像素插画、官方封面和详情图继续保留，联网下载的图片缓存另计。

## 安装

1. 从 Releases 下载 `runa-free-pocket-1.0.0.apk`。
2. **0.x 测试版需先卸载，再安装 1.0.0 并重新登录。**正式版改用专用发布签名，无法覆盖旧测试签名；卸载不会影响账号在官方商城已经拥有的游戏和素材。
3. 更新 Android System WebView 或 Chrome，打开应用，在“账号与同步”中连接 Epic 和 Fab。登录在官方页面完成。

正式版关闭应用调试与 WebView 调试，开启 R8 和资源压缩。发布签名证书 SHA-256：

`7f50e93e6634615432131e9ac7f6275dd7c8555b8b153ce7b85cad690570fb6c`

APK SHA-256：

`1244ab763661d60ad7d3ddd4872b77f061f46c540d1c1a8b49a69565ad05d4b9`

## 使用

- 点“一键领取本期全部”，依次刷新电脑游戏、手机游戏和 Fab，核实当前账号的拥有状态，再处理可领取内容。
- 也可以进入单个分类，全选或勾选后点“领取所选”。已拥有、未开始和已结束的内容会跳过；电脑与手机版本分别判断。
- 遇到验证码、年龄确认或首次许可协议时，在官方页面处理后继续。领取时保持应用在前台。
- “领取记录”显示确认入库、原已拥有、跳过、失败和待核实的项目，保留最近 20 次任务。
- 中断后点“继续未完成任务”。程序先重新读取活动和库存，只重试未提交的失败项；可能已经提交的订单只核实，避免重复自动下单。仍未确认的项目可从记录进入官网查看。
- 活动开始、结束以及回到应用时会检查是否需要刷新。预告可以查看拥有状态，开始前不可领取。
- 右上角可以切换账号或退出。Epic/Fab 共用本应用的网页会话，切换时一同退出；旧领取记录按原账号绑定，不能换一个账号重放。

只自动提交能够确认金额为零的订单。已拥有结论来自官方库存、权限或对应的官方商品页；同步失败、缺失商品标识和缓存缺项不会被当成“未拥有”。接口无法确认某一商品时，先只读核实官网，确认未拥有后才允许领取。

## 1.0.0 验证范围

2026-10-19，使用实际非调试、R8 压缩并正式签名的 APK，在独立 Android 12 / WebView 110 模拟器检查：

- 一次点击完成三类活动与真实库存读取，6 个已拥有项目与 2 个预告正确跳过，没有新增领取订单。
- 强制结束进程后恢复同一条任务，重新查库并完成；完整关闭、重新启动模拟器后再次检查通过。
- 360×616 CSS 像素竖屏下，4 张电脑封面、1 张手机封面和 3 张 Fab 封面实际解码正常；领取记录界面通过检查。
- 71 项 JavaScript、30 项 JUnit、原生分页检查与 4 种宽度的浏览器界面检查通过；Release 构建和 Lint 通过，0 错误、15 警告。

本次账号已经领完，**1.0.0 没有完成新的“未拥有→下单→入库”实测**。0.3.2 曾完成三类真实新增领取；本版沿用其网页结算规则，新增了任务记录和操作前的持久化许可。新增队列、金额拒绝与恢复行为通过独立测试；本机模拟页面的结果不算真实订单。用户自己的手机尚未连接验收。

## 开发与构建

用 Android Studio 打开本目录。需要 JDK 17+、Android SDK Platform 35、Build Tools 36，附 Gradle 9.3.0 Wrapper。

```powershell
# local.properties 配置自己的 SDK，例如 sdk.dir=D\:/Android/Sdk
./gradlew.bat assembleDebug testReleaseUnitTest
./tests/run-tests.ps1
```

正式签名资料放在仓库外，参照 [测试与签名说明](docs/TESTING.md) 配置自己的密钥：

```powershell
./gradlew.bat "-PrunaSigningProperties=D:/private/signing.properties" assembleRelease lintRelease
```

主要文件：`MainActivity.java` 管理安卓界面和队列；`ClaimJournal.java` 保存按账号绑定的进度和未确认操作；`EpicSession.java`、`EpicEntitlements.java` 读取授权与库存；`OfficialImages.java` 处理官方图片下载和缓存；`domain.js`、`automation.js`、`ui.js` 分别处理规则、官方网页和界面。

旧版本标签保留。`v0.1.0`、`v0.2.0`、`v0.3.0` 是从旧源码包补录的快照，之后为正常开发提交，详见 CHANGELOG。原始视觉资源保留于 `design/brand-source/`，不打入 APK；处理方式见 [品牌资源说明](docs/BRANDING.md)。

## 许可

工程代码采用 [GNU AGPL v3](LICENSE)，第三方组件保留各自许可，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。商城图片属于相应权利人。本项目与 Epic Games、Fab 无隶属关系。
