# 第三方组件与素材说明

runa周免口袋的工程代码采用 GNU AGPL v3，全文见 [LICENSE](LICENSE)。此说明补充第三方组件来源与各自许可证，不改变第三方组件的许可条件。

## Android 运行依赖

以下为 1.0.0 构建解析得到的直接及传递依赖；R8 会移除实际未使用的代码。这些依赖未修改源代码，其 Maven POM 均声明 Apache License 2.0。

| 组件 | 版本 | 上游 |
| --- | --- | --- |
| androidx.webkit:webkit | 1.12.1 | [AndroidX WebKit](https://developer.android.com/jetpack/androidx/releases/webkit) |
| androidx.annotation:annotation / annotation-jvm | 1.8.1 | [AndroidX Annotation](https://developer.android.com/jetpack/androidx/releases/annotation) |
| androidx.annotation:annotation-experimental | 1.4.1 | AndroidX Annotation |
| androidx.core:core | 1.1.0 | [AndroidX Core](https://developer.android.com/jetpack/androidx/releases/core) |
| androidx.lifecycle:lifecycle-runtime / lifecycle-common | 2.0.0 | [AndroidX Lifecycle](https://developer.android.com/jetpack/androidx/releases/lifecycle) |
| androidx.arch.core:core-common | 2.0.0 | [AndroidX Arch Core](https://developer.android.com/jetpack/androidx/releases/arch-core) |
| androidx.versionedparcelable:versionedparcelable | 1.1.0 | [AndroidX VersionedParcelable](https://developer.android.com/jetpack/androidx/releases/versionedparcelable) |
| androidx.collection:collection | 1.0.0 | [AndroidX Collection](https://developer.android.com/jetpack/androidx/releases/collection) |
| org.jetbrains.kotlin:kotlin-stdlib | 2.2.10 | [JetBrains Kotlin](https://github.com/JetBrains/kotlin) |
| org.jetbrains:annotations | 13.0 | [JetBrains Java Annotations](https://github.com/JetBrains/java-annotations) |

Apache 2.0 全文保留在 [WebKit 随包许可证](licenses/webkit-1.12.1-META-INF_androidx_webkit_webkit_LICENSE.txt)。已取得的依赖源码头部版权声明汇集在 [DEPENDENCY_COPYRIGHTS.txt](licenses/DEPENDENCY_COPYRIGHTS.txt)，包括 Android Open Source Project、JetBrains 及 Kotlin contributors 的原始声明。

同时保留 Kotlin 上游 `v2.2.10` 的 LICENSE、COPYRIGHT、NOTICE，以及 stdlib 源码注明参考的 GWT、Guava、ThreeTenBP 许可证，文件名以 `Kotlin-2.2.10-` 开头。上游 NOTICE 描述整个 Kotlin Compiler 分发，本 APK 仅使用构建解析的 stdlib，不捆绑 Kotlin 编译器。补充头部声明见 [DEPENDENCY_HEADER_NOTICES.txt](licenses/DEPENDENCY_HEADER_NOTICES.txt)。

## 构建、测试和设计工具

- 仓库附带 Gradle 9.3.0 Wrapper，Apache 2.0；原随包许可证见 [Gradle-Wrapper-LICENSE.txt](licenses/Gradle-Wrapper-LICENSE.txt)。Android Gradle Plugin 9.0.0 通过构建系统下载，不打入 APK。
- JUnit 4.13.2 和 org.json:json:20240303 只用于本地测试，不打入 APK；分别见 [JUnit 许可证](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt) 和 [JSON-java 许可证](https://github.com/stleary/JSON-java/blob/20240303/LICENSE)。
- 可选设计工具 Sharp 0.35.5 使用 Apache 2.0，具体工具及传递依赖见 [Sharp](https://github.com/lovell/sharp)。仓库不附带其安装文件，APK 不包含 Sharp；已提交转换后的 WebP，普通构建无需运行设计工具。
- 可选浏览器测试使用 Playwright 1.62.1，Apache 2.0，见 [Playwright 许可证](https://github.com/microsoft/playwright/blob/v1.62.1/LICENSE)。仓库不捆绑其运行时，APK 不包含 Playwright。原生测试只使用平台 API 和已有 AndroidX WebKit，不新增生产依赖。
- Android 系统提供 WebView；本工程没有内嵌 Chromium 浏览器或 Android 系统镜像。

## 实现参考与品牌素材

实现过程中参考了 [free-games-claimer](https://github.com/vogler/free-games-claimer)、[Free-Games-Claimer-Remaster](https://github.com/P-Adamiec/Free-Games-Claimer-Remaster)、[egs-api-rs](https://github.com/AchetaGames/egs-api-rs) 的公开流程和 Epic 官方文档。这些参考项目保持各自许可证，本仓库未捆绑其源码。

Logo 由项目拥有者提供，插画及图标补边使用 imagegen 根据该 Logo 制作，来源及处理过程见 [docs/BRANDING.md](docs/BRANDING.md)。原图在 `design/brand-source/`。代码开源不表示转让 runa 名称、Logo 或授予第三方商标权。

Epic、Fab 以及游戏和素材封面的权利属于对应权利人。商品封面与预览图运行时从官方服务读取，不作为仓库素材分发。本项目与 Epic Games、Fab 无隶属关系。

## 发布附件

1.0.0 的 APK 对应标签 `v1.0.0`，SHA-256 为 `1244ab763661d60ad7d3ddd4872b77f061f46c540d1c1a8b49a69565ad05d4b9`。发布页附完整源码、声明与许可证 ZIP、验证范围和校验值；APK 与实际安装验收的文件相同。签名私钥不随源码分发。
