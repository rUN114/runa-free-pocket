# 测试与签名

## 常规检查

需要 JDK 17+ 和 Android SDK 35。`tests/run-tests.ps1` 执行 JavaScript 检查，拥有凭证分页检查在 Gradle 单元测试中；Gradle 默认的单元测试变体为 Release。

```powershell
./tests/run-tests.ps1
./gradlew.bat testReleaseUnitTest lintRelease
```

浏览器界面测试使用可选的 Playwright 1.62.1 与已安装的 Edge，不调用官方领取接口，数据明确为示例。

```powershell
npm install --no-save --package-lock=false playwright@1.62.1
./tests/run-tests.ps1 -Browser
```

## 正式签名

自己生成并妥善备份 Android 发布密钥，签名属性文件放在仓库外。例如 `D:/private/signing.properties`：

```properties
storeFile=D:/private/runa-release.p12
storePassword=<your-store-password>
keyAlias=runa-release
keyPassword=<your-key-password>
```

```powershell
./gradlew.bat "-PrunaSigningProperties=D:/private/signing.properties" assembleRelease lintRelease
```

不提供该属性文件时 Release 不配置私钥，可编译未签名产物。仓库忽略密钥和签名资料；不要提交真实密码。其他开发者自行签名的 APK，不能覆盖由本项目发布密钥签名的安装包。

## 实际正式 APK 验收

`src/androidTest` 的 `ReleaseVerification` 是与目标应用使用相同签名的独立测试 APK，正式应用本身不包含它。默认 `inspect` 仅检查非调试包启动，不请求领取。以下步骤仅在隔离的测试设备及自己的账号上执行。

```powershell
./gradlew.bat "-PrunaSigningProperties=D:/private/signing.properties" assembleRelease assembleReleaseAndroidTest
adb install -r app/build/outputs/apk/release/app-release.apk
adb install -r -t app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
adb shell am instrument -w cn.run.freepocket.test/cn.run.freepocket.tests.ReleaseVerification
```

显式指定 `-e mode batch` 才会点击“一键领取本期全部”，会使用当前真实账号，可能领取尚未拥有的零元商品。首次登录和验证码需在官方页面处理。可在测试设备中预先登录；测试工具也支持临时迁移自己已有的官方 Cookie 会话，测试文件不随源码提供，不保存账号密码。会话文件使用后删除。

```powershell
adb shell am instrument -w -e mode batch -e portrait true cn.run.freepocket.test/cn.run.freepocket.tests.ReleaseVerification
```

`interrupt` 在任务检查点保存后主动终止进程，预期出现 `Process crashed`；这一步是明确的故障注入。随后使用 `resume`，应从暂停记录恢复，先查库再处理。比对 `interrupted.json` 与 `resume-real-account.json` 的任务 ID，不能只看最终列表。

```powershell
adb shell am instrument -w -e mode interrupt cn.run.freepocket.test/cn.run.freepocket.tests.ReleaseVerification
adb shell am instrument -w -e mode resume -e portrait true cn.run.freepocket.test/cn.run.freepocket.tests.ReleaseVerification
```

证据保存在隔离设备的应用外部文件目录 `Android/data/cn.run.freepocket/files/verification/`。其中可能含账号昵称，发布前应生成脱敏摘要，不直接公开原始文件和截图。完成后删除临时会话和邮箱文件、卸载测试 APK；正式 APK 可继续使用。

## 本机桥接模拟

`fixture` 必须使用独立的 `compact` 诊断构建。其本机页面拦截全部请求，只统计按钮点击，不向 Epic 提交订单；分别检查持久化许可后的单次点击和无许可时零点击。它不代表正式版创建过新订单，也不使用模拟数据证明真实账号拥有状态。

```powershell
./gradlew.bat -PrunaTestBuildType=compact assembleCompact assembleCompactAndroidTest
# 安装上述诊断 APK 和匹配的 compact AndroidTest APK 后执行：
adb shell am instrument -w -e mode fixture cn.run.freepocket.test/cn.run.freepocket.tests.ReleaseVerification
```

正式签名与诊断签名不同，在独立设备上分别测试，不互相覆盖。运行时及构建证据应分别记录版本、APK 哈希、签名和测试范围。

`windowFixture` 使用目标应用的商店 WebView，并拦截本机窗口测试的全部页面请求，验证没有触摸手势时能否打开窗口。应用启动仍可能读取官方目录或库存；夹具不请求真实订单。

```powershell
adb shell am instrument -w -e mode windowFixture cn.run.freepocket.test/cn.run.freepocket.tests.ReleaseVerification
```

`savedStateSnapshot` 只导出已保存的目录与账本，不启动界面、恢复 Cookie、同步库存或领取。输出含账号标识，只保存在本机私密测试目录，不直接上传。快照抓取时间不能代替条目的实际同步时间。

```powershell
adb shell am instrument -w -e mode savedStateSnapshot cn.run.freepocket.test/cn.run.freepocket.tests.ReleaseVerification
```

2026-10-31 的真实新订单、已拥有跳过及未验证部分见 [真实账号验证记录](LIVE-VERIFY-2026-10-31.md)。

## 1.0.0 的实际边界

正式包在独立 MuMu Android 12 / WebView 110 上，完成真实库存、全部已拥有跳过、进程中断恢复、完整模拟器关机重启与竖屏图片检查。原生许可桥接在诊断构建的本机页面验证。账号当时已拥有全部本期内容，没有新的真实订单；当前用户手机未连接测试。参照历史 0.3.2 新订单记录不能替代本版的新订单实测。

## Fab 账号标识与凭据

Epic 的 OAuth 令牌经 Android KeyStore 的 AES/GCM 密钥加密保存。Fab 的登录凭据是 WebView 管理的 Cookie，不是 `accountId`；Cookie 保存在应用私有 WebView 数据中，不能据此宣称 Cookie 也经过 KeyStore 加密。

Fab 的 `accountId`、显示名和同步时间作为库存及领取记录的绑定元数据存于应用私有 preferences。账号标识本身不能登录或领取，拥有状态查询仍需要有效 Cookie；它仍属于账号信息，不应写入公开日志或上传报告。发送到前端的 Fab 状态会删除内部 `accountId`。

## 1.0.1 审核修复回归

浏览器测试覆盖 320/360/390/480 宽度、数字价格和状态更新时清除已拥有/过期/已移除选择。`-Browser` 显式启用该测试，缺少 Playwright 或浏览器会报错，不会把跳过当作通过。

Fab 结算必须具有本次商品页许可确认，还须从结算许可控件或明确且唯一的许可摘要中核对同一档位。无法确认时暂停，不凭商品介绍中的 Personal/Professional 字样推断。英文免费卡片及已支持的中文文案均须有唯一且明确的零元货币金额；未支持的布局仍暂停，未据此宣称所有语言和官网改版兼容。

商店消息按任务类型、来源和主/子帧身份核对，Epic 的官方支付子帧仍受支持。格式错误消息不写失败账本；任务恢复的地区不匹配或无历史任务显示具体拒绝原因。内部账本错误仍停止处理。消息日志不记录 JSON、授权码或解析器异常内容。

正式版设备脚本与主 APK 分开压缩；测试代码需要的 Kotlin 运行方法不能因主应用没有直接调用而被裁掉。`app/proguard-device-tests.pro` 只保留现有脚本实际调用的运行入口，未关闭 R8 或保留整个 Kotlin 库。修改设备测试中的 Kotlin 调用后应重新运行压缩正式包的设备测试，并关注 APK 体积。
