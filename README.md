# runa周免口袋 · 原生界面版

当前分支为 **1.1.0-native-preview.2**。在安卓手机查看 Epic 电脑游戏、手机游戏与 Fab 限免素材，同步拥有状态并批量领取。最低 Android 8.0，无需电脑或自建服务器。公开 Releases 暂仍为旧版；原生版源码已随开发分支上传，尚未发布正式安装包。当前正式签名安装包589,182字节，约575KiB；保持在1MiB以内。

主目录、详情、账号设置、领取记录已改为 Kotlin 原生 Views/XML，使用 RecyclerView 与 ViewModel。官方登录、验证码、素材库读取及结算仍使用系统 WebView；网站适配脚本保留，未捆绑浏览器内核。

[本次修复与验证](docs/NATIVE-REVIEW-2026-11-01.md) · [版本记录](CHANGELOG.md) · [测试与签名](docs/TESTING.md) · [组件许可](THIRD_PARTY_NOTICES.md)

## 安装与使用

使用随本版交付的 APK。包名和正式签名沿用旧版；同签名可以覆盖安装，保留本机授权、设置和领取记录。签名不同的旧调试版无法覆盖安装，不能通过清空数据绕过账号或订单保护。

- “一键领取全部”依次刷新三类活动、核实当前账号拥有状态，再处理可领取项目；也可在分类里全选或逐项选择。
- 已拥有、未开始及已结束项目不可勾选，仍能查看详情与官网；电脑和手机版本独立判断。
- “账号”提供连接、重新授权、切换和退出，以及地区、Fab Personal/Professional 档位设置。Epic/Fab 共用网页会话，切换或退出会一起清理本应用的两类登录。
- “记录”保留最近20次任务及账号归属；待核实订单保留操作预约，继续任务先查库存，不会重新自动提交可能已提交的订单。
- 官方验证码、年龄确认或首次许可协议仍需本人处理；领取时保持应用在前台。网络诊断不发送账号 Cookie，也不代表领取成功。

只有官方库存、权限或对应商品页能确认拥有状态。同步失败、缓存缺项或缺失商品标识显示“未确认/同步失败”，不会当作“未拥有”。自动提交前必须确认金额为零，Fab 还须核实所选许可档位；已拥有素材不代表已取得所有档位授权。

## 当前验证边界

2026-11-01完成原生版构建、规则/协调器自动测试、旧账号环境副本升级、原生布局及官方图片检查。新增Fab编排回归、未放行预约写入失败恢复、设备强停恢复和可复跑的只读release脚本，详见验证记录。本次没有新增真实订单，也没有连接用户安卓真机验收；旧版真实新增领取的结果不代替本版验收。

## 开发

使用 Android Studio 打开本目录。生产 Kotlin 位于 app/src/main/kotlin/cn/run/freepocket，XML 位于 app/src/main/res。JDK 17+、Android SDK Platform 35、AGP 9.0.0（内置 Kotlin）、Gradle 9.8.2 Wrapper，固定官方 SHA256。

~~~powershell
# local.properties 示例：sdk.dir=D\:/Android/Sdk
./gradlew.bat assembleDebug testReleaseUnitTest
./tests/run-tests.ps1
# 原生诊断测试：只安装在独立模拟器/测试设备，不在正式包增加测试入口
./gradlew.bat -PrunaTestBuildType=debug assembleDebug assembleDebugAndroidTest
# 正式构建：签名配置保存在仓库外
./gradlew.bat "-PrunaSigningProperties=D:/private/signing.properties" assembleRelease lintRelease
~~~

Windows 路径含撇号时，JDK 的测试参数文件可能无法解析类路径。可复制工程到不含特殊字符的目录执行构建，保留原目录和 Git 历史。

| 位置 | 职责 |
| --- | --- |
| MainActivity.kt | 生命周期、系统边距、原生视图与官方浏览器接线 |
| ui/PocketViewModel.kt、PocketUiState.kt | 页面状态和明确的用户动作 |
| ui/NativePocketView.kt、各 Panel/Adapter | 原生目录、详情、账号和历史 |
| claim/ClaimCoordinator.kt | 领取队列、账号绑定、核实与恢复 |
| ClaimJournal.kt | 按账号绑定的账本及一次性操作许可 |
| model/、domain/ | 商品模型、活动时间、资格与拥有规则 |
| data/ | 兼容旧缓存/账本，HTTP与授权接口的异步边界 |
| web/OfficialBrowserController.kt | 官方网页、来源/nonce校验、脚本和网络提示 |
| EpicSession.kt、EpicEntitlements.kt | KeyStore加密授权及官方拥有凭证 |
| OfficialImages.kt、ui/NativeImageLoader.kt | 官方节点限制、缓存、异步解码与复用 |
| app/src/main/assets/{domain,automation}.js | 必须在官方会话运行的网站适配、零元结算保护 |
| tests/legacy-web/ | 旧网页界面与历史测试，仅作回归对照，不打入APK |

新依赖限于原生列表、生命周期、ViewModel与协程；没有加入Compose、Hilt、Room、通用网络库或字体包。品牌与组件规范见 [DESIGN.md](DESIGN.md)，原图和来源见 [品牌说明](docs/BRANDING.md)。

## 历史正式版

[公开 Releases](https://github.com/rUN114/runa-free-pocket/releases/latest) 仍对应旧版，不能把其APK当作本分支原生版。

## 1.0.0 验证范围

2026-10-19，使用实际非调试、R8 压缩并正式签名的 APK，在独立 Android 12 / WebView 110 模拟器检查：

- 一次点击完成三类活动与真实库存读取，6 个已拥有项目与 2 个预告正确跳过，没有新增领取订单。
- 强制结束进程后恢复同一条任务，重新查库并完成；完整关闭、重新启动模拟器后再次检查通过。
- 360×616 CSS 像素竖屏下，4 张电脑封面、1 张手机封面和 3 张 Fab 封面实际解码正常；领取记录界面通过检查。
- 71 项 JavaScript、30 项 JUnit、原生分页检查与 4 种宽度的浏览器界面检查通过；Release 构建和 Lint 通过，0 错误、15 警告。

本次账号已经领完，**1.0.0 没有完成新的“未拥有→下单→入库”实测**。0.3.2 曾完成三类真实新增领取；本版沿用其网页结算规则，新增了任务记录和操作前的持久化许可。新增队列、金额拒绝与恢复行为通过独立测试；本机模拟页面的结果不算真实订单。用户自己的手机尚未连接验收。

## 许可

工程代码采用 [GNU AGPL v3](LICENSE)，第三方组件保留各自许可。商城图片属于相应权利人。runa名称与Logo由项目拥有者提供，本项目与Epic Games、Fab无隶属关系。
