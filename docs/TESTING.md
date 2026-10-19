# 测试与签名

## 常规检查

需要 JDK 17+ 和 Android SDK 35。`tests/run-tests.ps1` 执行 JavaScript 与独立分页检查；Gradle 默认的单元测试变体为 Release。

```powershell
./tests/run-tests.ps1
./gradlew.bat testReleaseUnitTest lintRelease
```

浏览器界面测试使用可选的 Playwright 1.62.1 与已安装的 Edge，不调用官方领取接口，数据明确为示例。

```powershell
npm install --no-save --package-lock=false playwright@1.62.1
node tests/browser/ui.cjs
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

## 1.0.0 的实际边界

正式包在独立 MuMu Android 12 / WebView 110 上，完成真实库存、全部已拥有跳过、进程中断恢复、完整模拟器关机重启与竖屏图片检查。原生许可桥接在诊断构建的本机页面验证。账号当时已拥有全部本期内容，没有新的真实订单；当前用户手机未连接测试。参照历史 0.3.2 新订单记录不能替代本版的新订单实测。
