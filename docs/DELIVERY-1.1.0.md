# 1.1.0交付记录 · 2026-11-02

本次完成归档复核、离线/云端检查接线和本机正式签名APK交付。用户明确自行测试页面；本轮没有启动Android Studio、模拟器或应用，也没有执行账号操作和领取订单。

## 源码与产物

- 唯一日常开发入口：`D:/Android/Projects/runa-pocket-native`，分支`fix/native-preview-review`。此前桌面副本保留作旧资料，不继续平行编辑。
- 归一提交`f178c8b`组合评审修复`23dcd71`和并行快照`935854d`；`5a18f39`补探针异常测试，`21ac6c8`更新归一说明。当前交付源码以本地`v1.1.0`标签定位；旧标签不移动。
- 包名`cn.run.freepocket`，versionName `1.1.0`，versionCode `13`，minSdk 26、targetSdk 35。release关闭debuggable，开启R8及资源收缩。
- APK：590,014字节（约576KiB）。SHA256：`5f01995aee9a967fd554e35ba685115b8f141a8daaf45cdd1b5c2075dd3b0fb0`。
- 签名证书SHA256：`7f50e93e6634615432131e9ac7f6275dd7c8555b8b153ce7b85cad690570fb6c`，与原正式版相同，APK v2签名验签通过。密钥及签名属性仍在仓库外。
- 本机交付目录：`D:/Android/Builds/runa-delivery-1.1.0`，桌面另放同字节APK及校验文件。只推送开发分支，不合并安卓应用代码到main、不上传标签或创建GitHub Release。

## 本轮实际检查

| 范围 | 结果与边界 |
| --- | --- |
| JS | 90项通过，包括领取、零元金额、Fab档位和共享样本 |
| JVM | 109项，0失败、0错误、0跳过；含预约恢复和Fab编排 |
| Python | 13项上游异常样本、2项设备脚本摘要测试通过；无网络、无设备 |
| 构建 | 正式签名release、debug及debug androidTest编译通过；未安装运行 |
| Lint | 0错误、22警告，没有禁用检查制造通过 |
| 凭据扫描 | Gitleaks 8.30.1默认规则扫描交接基线21ac6c8可达的23个历史提交，未发现命中；本次待提交内容另行扫描，不是账号功能验收 |
| 归档 | 两个bundle恢复并fsck通过；两个新ZIP解压后140/142个文件分别与原目录逐字节一致 |

本轮生产代码只清理无效Lint抑制和过时注释，另增加版本号；没有改领取状态机、账号、WebView结算脚本、缓存或账本格式。

## 云端检查

GLM交接时，开发分支`21ac6c8`的[tests运行](https://github.com/rUN114/runa-free-pocket/actions/runs/41369833109)通过。默认分支`49ee66a`的[只读探针手动运行](https://github.com/rUN114/runa-free-pocket/actions/runs/41369974941)通过：两个Epic公开端点返回结构通过，Fab返回authWall，未验证登录后的内容。不能据此认定Fab真实领取可用。

此次开发分支CI新增离线Python、Lint和release构建，最终提交的云端结果以该提交关联的Actions检查为准；旧运行不能替代当前提交。本次不更改默认分支的定时探针，不重复新增定时任务。

## 备份及恢复

归档目录`D:/Android/Archive/runa-20261102`。原bundle与ZIP保留；GLM的ZIP缺中文文件名UTF-8标记，因此新增`runa-native-desktop-utf8.zip`、`runa-review-desktop-utf8.zip`，内部根目录使用英文。解压优先使用这两份新包。

`SHA256SUMS.txt`包含六份归档校验值；`SHA256SUMS.glm.txt`保留原四份清单；`RESTORE-VERIFICATION.json`记录完整恢复核对。bundle分别保留17/18个引用，镜像克隆后逐项核对并运行git fsck。ZIP排除Git元数据、IDE/构建缓存、本机SDK路径和签名密钥。旧源码目录未删除。

需要回看旧代码时，在新的英文目录克隆bundle；不要覆盖当前工程。需要回退已安装程序时，保留本机账本和账号数据，从目标源码构建更高versionCode并使用原正式签名；不要通过卸载清空待核实订单保护。

## 尚需用户验证

当前APK的真机覆盖更新、页面/图片、登录切换、三类库存与新的真实订单尚未验收。此次没有复跑11月1日的设备中断演练；此前记录仍属于此前构建。官网、验证码和网络变化可能需要后续适配，版本定名不代表这些外部条件永久不变。
