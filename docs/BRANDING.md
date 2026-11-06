# runa周免口袋视觉资源

## 来源与原图

用户提供的圆形 Logo 保存在 `design/brand-source/logo-original.jpg`。白发角色、黑猫、R 发夹与月夜配色由用户确认。

2026-10-18 使用 imagegen，以原 Logo 为角色和画风参考生成三张插画：

- game-night.png：电脑游戏礼箱。
- mobile-gift.png：手机游戏礼物。
- fab-workshop.png：Fab 创作素材台。

三张完整原图保存在 `design/brand-source/`，v0.4.0 标签也保留原始版本。0.4.1 使用 imagegen 对原 Logo 的外部黑色区域补画背景与下方桌面，形成没有圆形黑角的 `launcher-square.png`；它保留在同一设计目录。

设计原图不属于 Android 的运行资源目录，不打入 APK。代码许可证不代表对第三方商标或商城图片的授权；游戏和素材的真实封面继续从官方读取。

## 原生运行资源

1.1.0原生版将既有四张WebP原样移动/复制为app/src/main/res/drawable-nodpi/native_logo.webp、native_game_night.webp、native_mobile_gift.webp、native_fab_workshop.webp，未重新生成角色。历史网页资源保存在tests/legacy-web/brand，不打入APK。可选编码工具现在输出到原生drawable，不再生成生产assets/brand目录。

## 运行资源与压缩

- 三张插画：640px 宽，最近邻缩放保持像素轮廓，WebP quality 88、alphaQuality 100。
- 页面 Logo：192×192，WebP quality 90。
- 安卓图标：432×432，WebP quality 94；由填充后的方形图生成。
- RGB 使用高质量有损编码，透明通道保留，不能把缩放与重编码称为原图无损压缩。

可选设计工具为 Sharp 0.35.5。已提交运行资源，普通 APK 构建不需要安装 Sharp；要修改设计图后重新编码，可在项目目录执行：

```powershell
npm install --no-save --package-lock=false sharp@0.35.5
node tools/optimize-brand-assets.cjs
```

## 安卓图标

自适应图标使用铺满的夜空蓝背景和前景图片，按 Android 的 108dp 图层、72dp 可视区域留出 16.666667% 的前景内缩；圆形或圆角遮罩由系统桌面处理。另保留普通位图回退资源，不在图片中预画圆角或黑色框。

参考：[Android AdaptiveIconDrawable](https://developer.android.com/reference/android/graphics/drawable/AdaptiveIconDrawable)。实际验证为 Android 12 的 Lawnchair 桌面圆角图标，其他手机与遮罩不称作已直接测试。覆盖更新时桌面可能显示旧缓存，重新启动桌面或手机后查看新图标。

## 界面约定

- 夜空背景 #071329，主视觉深蓝 #092657，面板 #11213c。
- 奶油白文字 #fff3dd，月光金操作 #ffd279，退出操作辅以珊瑚色 #ffc6bd。
- 系统字体用于正文；像素感集中在 Logo 和插画，未引入字体服务。
- 原生目录按实际dp宽度分为1/2/3列（600/1000dp断点），头部随列表滚动，底部领取操作固定；检查320dp与大字号。旧网页740px高度规则只保留在历史界面。
