# 周免口袋 0.3.1

Android 上查看并领取 Epic 电脑游戏、安卓游戏和 Fab 限时免费内容的本地工具。

本版修复安卓图片下载的地区节点跳转。已将最终 APK 安装到独立 MuMu Android 12 环境，使用用户授权的真实账号完成登录和库存同步，并确认图片在 APK 的 WebView 中实际解码、显示。用户自己的手机尚未通过 USB 连接验收。

## 安装与使用

1. 安装 `周免口袋-0.3.1-测试版.apk`，可直接覆盖旧版，不需要卸载。包名、签名不变，版本 code 为 4。
2. 打开后自动读取电脑活动并同步已有 Epic 授权。切换手机游戏或 Fab 分类，可刷新活动、同步库存。
3. Epic 和 Fab 使用官方网页登录，账号凭据保存在应用私有存储；Epic 会话使用 Android Keystore 加密。本程序不提供代收账号密码的服务器。
4. 卡片显示图片、名称、活动时间、平台和拥有状态，点击查看详情与预览图。可以隐藏已拥有、全选或选择本期内容。
5. “即将推出”显示官方已公布的预告和独立拥有状态，开始前不能选择或领取；开始后刷新活动。未公布的手机或 Fab 预告不会编造显示。
6. 领取前重新同步并跳过已拥有，只提交明确合计为零的订单。验证码、年龄确认和许可协议由用户处理，领取后重新核实结果。

最低 Android 8.0，需要支持安全网页消息和页面启动脚本的 Android System WebView。活动和首次图片下载需要联网，程序在前台运行。卸载或清除应用数据会移除本机登录状态。

## 这次查到的图片问题

在安卓中运行 0.3.0 复现失败，下载器记录了 `Image HTTP 302`。同一 Epic 图片地址在该网络下从 `cdn1.epicgames.com` 跳转到 `cdn1-epicgames-1251447533.file.myqcloud.com:443`。上一版禁止自动跳转，直接将 302 当作失败；此前 Windows 下载测试没有覆盖这条地区路径。

本版逐次处理 301/302/303/307/308，最多跟随四次跳转；每个目标都检查 HTTPS 和图片节点，只增加此次从 Epic HTTPS 响应实际观察到的精确腾讯云节点，不放行整个云存储域名。保留图片大小、格式头、缓存和同一原图重试检查。

同时使用可并发创建目录的标准方法，避免多张首图同时下载时争用缓存目录。调试构建启用 WebView 调试，图片错误保留具体日志，便于以后在安卓环境定位问题。

## 实际验证（2026-10-17）

最终安装包 SHA-256：`eaf6ce92091aeca9ddbf4450cdfc66ac9b62e6a968bd158f30c9daa81abec9ec`。通过安卓已安装 APK 的 SHA-256 与交付文件比对，确认实际测试的是这份安装包。

| 验证 | 实际结果 |
| --- | --- |
| 环境 | 独立 MuMu Android 12，System WebView 110；运行实际 APK，真实账号，没有注入示例库存 |
| 电脑游戏 | Bad Cheese、Agent A 电脑版均同步为已拥有；两项预告分别同步为未拥有 |
| 安卓游戏 | Agent A 安卓版同步为已拥有，与电脑版本分别核实 |
| Fab 素材 | 本期三项素材均从当前账号同步为已拥有 |
| 封面 | 清除应用公开图片缓存后重新启动，4 张电脑封面（含 2 项预告）、1 张安卓封面、3 张 Fab 封面全部实际解码显示，共 8 张 |
| 详情图库 | Bad Cheese 详情封面显示，横向逐张查看的 7 张预览图全部实际解码显示 |
| 防止重复领取 | 当前真实账号的本期内容均已拥有，领取按钮保持禁用；没有为测试创建新订单 |
| JavaScript 与分页 | 57 项 JavaScript 回归测试、原生完整分页检查通过 |
| 原生代码测试 | 16 项 JUnit 测试通过，包含地区 302、其他跳转状态、相对地址、未知节点、HTTP 降级、循环、超限、最终错误、图片大小和并发缓存创建 |
| 构建与静态检查 | assembleDebug、lintDebug 通过，Lint 0 错误、15 警告；未关闭规则 |
| 签名 | V2 验证通过，签名证书与旧版一致，可覆盖安装 |

实际安卓截图另存于交付目录，文件名带“安卓实测”。它们是 APK 的真实页面，使用真实库存；旧版标为“界面预览”的截图是测试样例，两者不混用。

本机 Windows 用户路径含单引号，Gradle 测试工作进程的参数文件不能正确解析类路径。测试类正常编译后，以同一测试类路径直接运行 JUnit，16 项通过；没有删测试或放宽规则。

本次没有连接用户手机，也没有测试领取新商品。模拟器中的实际登录、库存读取和图片显示不能扩大描述为所有手机、网络和未来网页都已验证。

## 拥有状态

- 已拥有：当前账号的有效凭证、准确商品权限、官方商品页或 Fab 已获取素材库确认。
- 已拥有 · 缓存：上次确认拥有，尚未完成本次同步，显示上次时间。
- 未拥有：准确商品权限或完整 Fab 素材库确认未拥有。
- 部分已拥有：组合商品只拥有部分内容，其余部分已核实。
- 未能确认 / 同步失败：尚未连接、缺少资料，或请求、授权、解析失败；不能据此认定未拥有。

电脑与安卓使用独立商品标识，凭证缺项不直接认定未拥有。预告是活动阶段，与拥有状态分别显示。

## Git 与代码

本目录是源码仓库，主分支 `main`。`v0.1.0`、`v0.2.0`、`v0.3.0` 从原源码包补录；`v0.3.1` 对应本次真实修复和验证。变化见 [CHANGELOG.md](CHANGELOG.md)，后续修改约定见 [AGENTS.md](AGENTS.md)。尚未配置或推送 GitHub 远程仓库。

```powershell
git log --oneline --decorate
git diff v0.3.0..v0.3.1
```

交付目录保留 `周免口袋-Git历史-0.3.1.bundle`，包含完整提交和标签，可用 `git clone "bundle的完整路径" epic-pocket` 恢复。普通源码 ZIP 不含 Git 历史。

主要文件：MainActivity 管理安卓页面与队列；EpicSession、EpicEntitlements 读取授权和库存；OfficialImages 处理图片下载与缓存；assets 中的 domain、automation、ui 分别负责业务规则、官方网页流程和界面。`ImageRedirectTest` 专门回归本次地区跳转问题。

构建需要 JDK 17+、Android SDK Platform 35、Build Tools 36，附 Gradle 9.3.0 Wrapper。生产依赖仍只有 AndroidX WebKit 及传递依赖。JUnit 和真实 JSON 解析库仅用于测试，不打入 APK。

```powershell
# 工程目录执行；local.properties 配置自己的 SDK，例如 sdk.dir=D\:/Android/sdk
./gradlew.bat assembleDebug lintDebug testDebugUnitTest
./tests/run-tests.ps1
```

真实账号文件、Cookie、会话记录和参考 APK 不在源码或 Git 中。历史实现参考 [free-games-claimer](https://github.com/vogler/free-games-claimer)、[Free-Games-Claimer-Remaster](https://github.com/P-Adamiec/Free-Games-Claimer-Remaster)、[egs-api-rs](https://github.com/AchetaGames/egs-api-rs)，商品权限依据 [Epic 官方说明](https://dev.epicgames.com/docs/web-api-ref/ownership-verification-using-rest?lang=ja)。本工程未复制小黑盒或 C5Game 的品牌、业务脚本、私有后台和账号体系。

本工程采用 GNU AGPL v3，见 LICENSE。官方图片属于相应权利人，通过商城在线加载。本程序与 Epic Games、Fab 无隶属关系；当前安装包使用测试签名。
