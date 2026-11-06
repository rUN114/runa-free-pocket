# 1.1.0-native-preview 原生版验证

验证日期：2026-11-01。基线4087d48，原生分支refactor/native-ui。原工作区、旧桌面源码和原账号模拟器均保留；仅在其独立副本上安装测试。

## 构建与安装包

- versionCode 11；API 26–35；AGP9内置Kotlin；Views/XML、ViewBinding、RecyclerView、ViewModel、Coroutines。
- 正式签名、非调试、R8/资源收缩安装包：**583,594字节，约570KiB**。
- APK SHA256：95458a950318dabf1c8c1cc77874f661a5383c7c1793e46e76da42304092ff41。
- 签名证书SHA256：7f50e93e6634615432131e9ac7f6275dd7c8555b8b153ce7b85cad690570fb6c，沿用旧正式签名。
- 生产资源不再包含index.html、ui.js、style.css及重复brand资产；只保留网站适配脚本及原生品牌drawable。
- release构建、82项JVM、Lint完成，0错误、23警告；debug同样82项JVM通过，两者不是164个不同用例。
- 88项JS通过；旧网页界面4种宽度回归通过，该结果只证明历史界面回归，不代替原生测试。

Lint警告未隐藏，主要包括已由Activity预检的WebKit特性调用、官方页面所需JavaScript、列表全量刷新建议、运行时设置的图片描述、平台版本属性及KTX建议。未禁用检查规则。

## 旧数据与实际账号读取

在旧账号模拟器副本覆盖升级，保留应用UID/数据及密钥。对比旧版快照与升级后账本：对象内容完全一致，保留2条历史任务和1条操作预约。没有清空待核实状态或测试账本。

非调试release载荷在副本中启动，系统UI树确认目录使用原生控件，没有目录WebView；读取到原账号并完成PC官方活动/拥有凭证同步。手机分类同样完成官方活动和拥有状态读取，原来已领取的手机游戏显示已拥有。Fab分类也完成官方活动及素材库读取，3项已拥有素材正确跳过勾选，实际官方封面显示正常。真实网络图片与debug缓存样例分别检查。

为兼容原测试环境的安装签名，模拟器安装的是正式release载荷重新以测试密钥签名的副本；生产交付APK使用正式密钥并通过验签。重新签名不改变DEX/资源，但不等于已在用户真机上验证生产签名覆盖升级。

## 原生设备样例与浏览器

debug诊断宿主不创建账号后端和领取协调器；示例动作只记录回调，不执行真实订单：

| 条件 | 结果 |
| --- | --- |
| 320dp竖屏、字体1.3 | 三分类、48dp操作、拥有/预告不可选、详情/账号/记录、处理中限制通过 |
| 360dp竖屏、标准字体 | 同上；Logo、标题、图片与底部按钮正常 |
| 780dp横屏、标准字体 | 双列与全宽状态/标题正常，未见遮挡 |
| 本机PNG缓存 | 封面及预览实际解码，视图无目录WebView；不冒充真实网络图片 |
| window.open拦截页 | 结算窗口导航成功，无订单 |
| 旧nonce与脚本替换 | 旧消息被拒绝；同id不导航，新文档与同源子帧获得更新脚本 |
| 无响应网页适配 | 实际等待40秒后显示读取超时 |

独立上下文审核发现初始状态取消读取超时的问题，修复并补JVM和设备回归后复核未发现新增确定缺陷。审核只读复核代码、报告和截图，不算审核员亲自执行全部用例。

## 复现

先在无真实订单的独立模拟器构建、安装debug诊断包与其测试APK：

~~~powershell
./gradlew.bat -PrunaTestBuildType=debug assembleDebug assembleDebugAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -e mode fixture cn.run.freepocket.test/cn.run.freepocket.tests.NativeVerification
adb -s SERIAL shell am instrument -w -e orientation landscape -e mode fixture cn.run.freepocket.test/cn.run.freepocket.tests.NativeVerification
adb -s SERIAL shell am instrument -w -e mode browserFixture cn.run.freepocket.test/cn.run.freepocket.tests.NativeVerification
adb -s SERIAL shell am instrument -w -e mode timeoutFixture cn.run.freepocket.test/cn.run.freepocket.tests.NativeVerification
~~~

fixture、browserFixture、timeoutFixture仅用于debug。snapshot模式也只导出账本哈希/计数，不导出账号凭证；inspect模式在debug中查看原生启动。正式APK用系统UI自动化检查，不为独立测试APK保留生产类名和Kotlin库入口。

设备证据保存在本机私密测试输出目录；其中真实页面截图可能带账号显示名，不随开源源码分发。生产源没有账号文件、Cookie、会话和签名密钥。

## 尚未验证及恢复边界

本版没有新增真实领取订单，不能把旧版Galaxy Kart的真实领取记录算成本版新增订单验收。当前用户手机未连接，未验证其厂商系统WebView、当地网络和安装更新。验证码、首次协议及官方页面变化仍可能需要处理；大陆网络质量不能由重构保证。

发布前保留旧源码和可重建安装包。本次没有公开推送或GitHub发布。保留数据回退应从旧实现构建更高versionCode并使用原签名，避免卸载丢失本机未确认订单保护。
