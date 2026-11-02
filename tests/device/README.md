# 原生设备检查

这些脚本不清除应用数据。必须显式提供设备序列号，建议仅使用独立模拟器。截图/XML可能含账号显示名，输出放在仓库外或被忽略的work/private目录，不要上传。

## debug：隔离进程中断与存储

先安装debug APK及其androidTest APK：

~~~powershell
./gradlew.bat -PrunaTestBuildType=debug assembleDebug assembleDebugAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python tests/device/recovery_smoke.py --adb adb --serial SERIAL --output D:/private/native-recovery.json
~~~

脚本等待隔离任务保存product/submit许可检查点，再强停进程；新进程重新加载后只核实，保留待核实预约，不能重新发许可。假目录/账号/桥消息不访问官网，偏好文件与正式账号账本完全分离。这是设备故障注入，不是新增真实订单，也不证明release混淆后的完整下单链路。

同一脚本测量40次主线程SharedPreferences commit，样本约76KB、一条180项run；不含序列化成本，不代表最大历史/用户真机性能。

其他诊断入口：

~~~powershell
adb -s SERIAL shell am instrument -w -e mode uiReview cn.run.freepocket.test/cn.run.freepocket.tests.NativeVerification
adb -s SERIAL shell am instrument -w -e mode browserErrors cn.run.freepocket.test/cn.run.freepocket.tests.NativeVerification
adb -s SERIAL shell am instrument -w -e mode browserFixture cn.run.freepocket.test/cn.run.freepocket.tests.NativeVerification
adb -s SERIAL shell am instrument -w -e mode timeoutFixture cn.run.freepocket.test/cn.run.freepocket.tests.NativeVerification
~~~

timeoutFixture实际等待40秒，并持续更换waiting消息，核实提醒不会被取消。browserErrors检查错误初始地址与实际WebView连接错误脱敏。

## release：只读系统UI冒烟

安装非调试release载荷；脚本自行检查安装标志与版本，不依赖生产中的测试入口。

~~~powershell
python tests/device/native_release_smoke.py --adb adb --serial SERIAL --output D:/private/native-release-smoke
~~~

只操作分类、账号面板、记录和返回，不点击领取、连接、退出或继续任务。先等空闲再做一次冷启动，随后读取三类活动/库存、检查可见封面、保存截图及摘要。官网登录或网络阻断时脚本超时失败，需本人处理后重跑；不会假称通过。正在运行的真实领取任务不要用这个脚本测试。

正式签名与旧测试签名不同，不能互相覆盖；本机发布验收采用相同release载荷的测试签名副本，不代表用户真机上的生产签名更新已验收。
