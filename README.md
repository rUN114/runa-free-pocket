# 周免口袋 0.1.0

Android 本地运行的 Epic / Fab 免费内容领取测试版。没有自建服务器，安装后在手机里的官方网页登录。

**这是可安装的初版 APK，尚未在安卓真机上完成登录、拥有状态同步及实际领取验证，不能视为已经验证稳定的一键领取成品。** 代码和测试一并提供。网页结构、验证码或地区限制可能让自动流程暂停，需要手动处理。

## 使用

1. 将随附 `周免口袋-0.1.0-测试版.apk` 传到安卓手机，点击安装。系统要求时，允许当前文件管理器安装应用。最低 Android 8.0；Android System WebView 需要支持网页消息和页面启动脚本，过旧时程序会提示更新。
2. 打开程序，先把“账号地区”设成 Epic 账号的真实地区。默认选项为中国大陆，不会自动判断账号地区。
3. 在“电脑游戏”点击“登录 / 管理”，在 Epic 官方页面登录。使用 Fab 时，也在 Fab 分类点击一次“登录 / 管理”，完成 Fab 的授权和首次协议。
4. 返回列表，分别刷新电脑游戏、安卓游戏和 Fab 素材。它们使用各自的官方活动来源。
5. 点击“核实拥有状态”，逐项检查当前账号。支持选择全部或勾选部分内容；点击“一键领取”后按队列处理，已确认拥有的项目会跳过。
6. 网页要求验证码、年龄确认、许可选择或协议时，在官方页面处理。正常情况下程序继续读取结果；若订单已经完成但页面未自动更新，点击顶部“核实结果”。只有商品主按钮或 Fab 素材库确认后才会显示“已拥有”。

“返回列表”会停止当前队列。“跳过此项”会处理下一项。已经提交但未确认的订单可能仍在服务端处理，请核实后再重试。程序需要保持前台，不提供手机锁屏后的定时领取。

## 内容与状态

- 电脑游戏：读取 Epic 当前免费促销，过滤尚未开始、已结束和非零价格的商品，并显示截止时间。
- 安卓游戏：读取移动端 `freeGame` 活动；安卓商品独立于电脑版，按实际安卓商品地址领取。
- Fab 素材：读取官方限时免费活动标记；支持全选、单选。Fab 限时免费目前通常每两周更新，活动时间以官网为准。
- “待核实”：尚未读取当前账号的权属，或刚换账号、换地区、重启程序。
- “未拥有”：官方商品主按钮可领取，或完整的已登录 Fab 素材库未包含该素材。
- “已拥有”：官方商品主按钮或已获取的素材库记录确认拥有。点击领取按钮、本地历史记录和付款成功提示都不能单独确立这一状态。
- “当前不可领 / 核实失败”：商品无法免费领取、地区不适用，或请求/页面解析失败；不会伪装成“未拥有”。

## 验证情况（2026-10-16）

| 项目 | 实际结果 |
| --- | --- |
| JavaScript 单元与网页流程测试 | 34 项通过，包含活动有效期、安卓独立商品、拥有状态、HTTP 401、重复选择、零元/付费/不明金额订单和重复提交保护 |
| Android 构建 | `assembleDebug` 通过 |
| Android Lint | `lintDebug` 通过，0 个错误；仍有版本提示、跨方法 WebView 前置条件提示、中文文本拼接等 13 个警告，未禁用规则 |
| APK | V2 签名校验通过；包名 `cn.run.freepocket`，版本 `0.1.0`，最低 API 26，目标 API 35 |
| 界面 | 390px 手机宽度检查，无横向溢出；全选跳过已拥有项目，长标题可换行。随附预览截图使用明确标出的测试数据 |
| 电脑活动接口 | 原生 HTTP 访问返回 200，解析出当期 Bad Cheese 和 Agent A: A puzzle in disguise；此为当时活动快照 |
| 安卓活动接口 | 浏览器内访问返回 200，读到 Agent A: A puzzle in disguise 的独立安卓活动，截止时间为返回数据中的 2026-10-22T15:00:00Z |
| Fab 实站 | 当前浏览器连接关闭，未完成当期素材接口与真实素材库验证；实现参考下列 GitHub 源码 |
| 登录和实际领取 | 未验证。没有连接安卓设备，也未使用用户账号下单 |

## 账号与订单

账号在官方网页中输入，程序不提供代收密码的服务器。WebView 的登录 Cookie 和活动缓存保存在应用私有目录。应用关闭云备份与设备迁移；卸载/清除应用数据会移除本地登录状态。官方页面仍会正常连接它自身的资源和登录、验证服务。

网页脚本和消息只授权 `store.epicgames.com`、`www.epicgames.com`、`www.fab.com` 三个确切的 HTTPS 来源。应用界面使用独立本地来源，商城网页无法调用应用界面的命令通道。验证码和身份确认交给用户；不伪造年龄，不自动勾选营销选项或许可协议。

自动提交前必须读到明确的合计金额为零；付费或无法解析的金额会停止自动下单。网页变化时可能需要手动领取。无账号实测前，不能保证所有页面和地区都支持自动流程。

## 源码与构建

- `app/src/main/java/cn/run/freepocket/MainActivity.java`：手机界面、官方浏览器、领取队列、消息来源校验、状态和缓存。
- `app/src/main/java/cn/run/freepocket/EpicPublicApi.java`：电脑游戏公开活动请求，不携带登录凭据。
- `app/src/main/assets/domain.js`：活动筛选、商品标识、零金额判断与选择过滤。
- `app/src/main/assets/automation.js`：官方页面与付款框中的检查和领取动作。
- `app/src/main/assets/index.html`、`style.css`、`ui.js`：手机列表界面。
- `tests/`：Node.js 自带测试框架，无测试依赖安装要求。

构建需要 JDK 17 或更新版本、Android SDK Platform 35、Build Tools 36，以及能下载 Google Maven / Maven Central / Gradle 的网络。工程附有 Gradle 9.3.0 Wrapper，首次使用会下载 Gradle；也可以使用已安装的 Gradle 9.3.0。

```powershell
# 在工程目录创建 local.properties，填入你自己的 SDK 路径（冒号与反斜杠需转义）。
# 示例：sdk.dir=D\:/Android/sdk
./gradlew.bat assembleDebug lintDebug
node --test tests/*.test.cjs
```

APK 生成于 `app/build/outputs/apk/debug/app-debug.apk`。随附 APK 使用测试签名，不是应用商店发行包；重新构建或发布时应自行维护签名密钥，不要提交密钥到仓库。

## 参考实现

查阅了以下项目，安卓界面和任务控制重新实现，未把电脑浏览器或 Docker 项目直接打包成手机应用。

- [vogler/free-games-claimer](https://github.com/vogler/free-games-claimer)，查阅提交 `99c1f05302aeece21a628797cfdffb561ee38956`。参考 Epic 商品主按钮判断、浏览器登录和领取流程。
- [P-Adamiec/Free-Games-Claimer-Remaster](https://github.com/P-Adamiec/Free-Games-Claimer-Remaster)，查阅提交 `611f5653acc22c6c850f600ddf58fb998609d5da`。参考安卓活动接口、Fab 限时活动标记、素材库分页和跨来源付款框处理方式。原项目依赖桌面 Chrome / Python，这里采用 AndroidX WebKit 的来源限制脚本与消息接口。
- [FabCLI](https://github.com/zirklerite/FabCLI)：查阅了项目说明，作为账号授权和素材库工具的另一条路线；没有运行或移植其实现。
- [Fab 官方免费内容说明](https://forums.unrealengine.com/t/new-limited-time-free-content-program-on-fab/2092736)。

本工程以 GNU AGPL v3 发布，见 `LICENSE`。AndroidX WebKit 及其依赖采用各自的开源许可，由 Gradle 从官方仓库解析。完整源码与 APK 一并交付，未包含个人账号、Cookie、密码或签名私钥。此项目与 Epic Games / Fab 无隶属关系。
