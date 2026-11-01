# 原生版结构与兼容约束

生产目录：app/src/main/kotlin/cn/run/freepocket。应用仍使用现有包名、正式签名和Android 8.0最低版本。

## 状态与边界

MainActivity只负责Activity生命周期、系统边距、返回键和视图接线。PocketViewModel持有页面选择、分类及StateFlow，调用领取协调器；业务处理不依赖屏幕控件或隐藏的本地WebView。

CatalogItem、Category、Ownership、Availability、FabTier是原生模型。CatalogRules处理PC活动解析、活动起止、组合商品拥有规则与可选资格。JSON保留在外部服务、旧账本/缓存和官方网页消息边界；日期输出ISO文本，兼容既有脚本。

AndroidPocketBackend封装SharedPreferences、EpicSession和HTTP的异步边界；PocketBackend/EpicAccess只隔离真实外部I/O，供协调器故障测试替换。ClaimCatalogue负责兼容旧目录数组，避免UI处理JSON。

## 领取编排

ClaimCoordinator拥有队列和当前任务，不持有Activity或View。每次网页任务有新的nonce；旧回调不影响新任务。ClaimJournal在主机许可回复前同步保存操作预约，写入失败不会给网页授权。reserve返回仅在未回复许可时可回滚的检查点；失败只回退该阶段并暂停，恢复存储后先保存回退状态。此前已发出的product预约不会随submit写入失败撤销。next以迭代泵推进，避免同步失败递归。

账号、地区、许可档位与原任务不符时拒绝重放；可能已提交的订单只进行拥有核实。返回、跳过、读请求重试和领取提交分别处理，不用自动重试订单解决网络故障。

网页初始标题通过BrowserRequest传递，不被当作网页响应。40秒读取提醒独立于waiting/error更新；达到截止时追加提醒，保留具体提示，不自动终止验证或结算。BrowserReadStatus负责该状态。

## 原生UI

NativePocketView组合目录、详情、账号和历史面板。CatalogAdapter以DiffUtil复用并更新实际变化的卡片；NativeImageLoader在IO线程下载、解码，取消已复用视图的任务，并以绑定标记避免错图。下载/解码最多2并发，内存缓存不超过16MiB并按可用堆调整。沿用OfficialImages的官方主机限制、磁盘缓存与中国CDN跳转规则。

页面通过PocketAction表达用户意图，控件不直接写账本或发送订单请求。详情和官网按钮不等于领取。已拥有、预告、已结束、缓存未确认及处理中状态由规则约束选择。

OfficialBrowserController仅管理官方网页、脚本、来源受限消息、网络提示和会话清理。初始请求仅允许三处官方HTTPS来源及默认端口，坏地址通过当前请求id报告失败；网页内部HTTPS导航继续保留。id相同的脚本替换不导航，供Fab在product许可后更新后续iframe脚本；新id销毁旧页并使旧回复失效。MainActivity在许可回复前同步安装新的脚本。

## 数据与账号兼容

保留catalogue/schema=3、claim-journal/data、epic-auth/encrypted、KeyStore别名、图片缓存路径和账号清理标记，不升级数据库格式。Epic授权保持KeyStore加密；Fab的accountId只是库存归属标识，登录凭证仍是系统WebView Cookie，单独的accountId不能登录账号。

切换或退出会清理Epic和Fab的共享网页会话及本机授权；账本及旧账号归属保留。清理标记同步保存，清理失败不假称退出完成。

## 仍保留的JavaScript

app/src/main/assets/domain.js和automation.js在官方页面内读取手机/Fab活动、库存、结算信息，等待验证码并执行经过许可的零元操作。它们属于网站适配层。主目录、PC活动与拥有规则不再通过evaluateJavascript计算。

旧HTML、CSS、ui.js和旧设备测试位于tests/legacy-web，仅用于历史回归，不进入安装包。debug专用NativeFixtureActivity与独立NativeVerification也不进入正式包。

## 修改与回退

先运行相关JVM/网页适配测试，再检查非调试APK与实际原生页面。读取库存、界面样例、网络故障注入和真实新增订单分别记录。

旧工作区及旧桌面源码保持不变。若要保留数据回退界面，可从4087d48的新分支构建更高versionCode并使用原签名；Android通常不允许直接安装较低版本，不能通过卸载丢掉未确认订单保护来代替回退。
