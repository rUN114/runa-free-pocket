---
name: runa周免口袋
description: 延续月夜像素品牌的 Android 原生限免目录
colors:
  night-background: "#071329"
  night-surface: "#11213C"
  night-surface-variant: "#193252"
  cream-text: "#FFF3DD"
  mist-text: "#C3D2E7"
  moon-gold: "#FFD279"
  gold-ink: "#241B0B"
  coral-error: "#FFC6BD"
  mint-success: "#B8DEC9"
typography:
  headline:
    fontFamily: sans
    fontSize: 20sp
    fontWeight: 700
  brand-title:
    fontFamily: sans
    fontSize: 18sp
    fontWeight: 700
  title:
    fontFamily: sans
    fontSize: 17sp
    fontWeight: 700
  body:
    fontFamily: sans
    fontSize: 14sp
  label:
    fontFamily: sans
    fontSize: 14sp
spacing:
  compact: 4dp
  related: 8dp
  frame: 12dp
  content: 16dp
  panel-heading: 20dp
components:
  button-primary:
    backgroundColor: "{colors.moon-gold}"
    textColor: "{colors.gold-ink}"
    typography: "{typography.label}"
  button-primary-disabled:
    backgroundColor: "{colors.night-surface-variant}"
  button-secondary:
    backgroundColor: "{colors.night-surface-variant}"
    textColor: "{colors.cream-text}"
    typography: "{typography.label}"
  nav-selected:
    backgroundColor: "{colors.moon-gold}"
    textColor: "{colors.gold-ink}"
  catalog-card:
    backgroundColor: "{colors.night-surface}"
  catalog-card-selected:
    backgroundColor: "{colors.night-surface-variant}"
---

# Design System: runa周免口袋

## Overview

**Creative North Star: "月夜口袋"**

延续已有品牌：深蓝月夜承托奶油白文字，月光金突出领取操作和当前分类。白发角色、黑猫、R 发夹及月夜像素画来自已确认的 Logo 与三张场景插画；“月夜口袋”是对现有视觉的记录名称，不是重新命名产品或重新设计品牌。

商品封面、状态和操作使用 Android Views/XML，正文与控件沿用系统字体。像素风保留在图片中，文字清楚、状态具体、操作直接。原生目录、详情、账号与记录共用夜色主题；官方认证和结算进入独立的白色 WebView 区域。

**Key Characteristics:**
- 深蓝底色、奶油白文字、月光金主操作。
- 原生系统字体与控件，像素 Logo 和插画延续品牌。
- 商品图片与可读状态并列，已拥有、未确认和失败分别表达。
- 列表滚动，分类及领取操作保留在目录框架内。

依据：`PRODUCT.md`、`docs/BRANDING.md`、`app/src/main/res/values/native_theme.xml`、`res/color/native_*.xml`、`res/layout/native_*.xml` 及 `app/src/main/kotlin/cn/run/freepocket/ui/`。此处的 dp/sp 是 Android 原生单位；前言 token 以当前资源为准，不转换成网页尺寸。

## Colors

颜色值集中在前言，名称描述其在当前原生界面中的作用。主题是固定月夜配色，当前代码没有实现动态取色或另一套浅色原生主题。

### Primary

- **月光金**（`moon-gold`）：一键领取全部、选中的分类、商品剩余时间。
- **金底深墨**（`gold-ink`）：月光金按钮及选中分类上的文字。

### Neutral

- **深蓝月夜**（`night-background`）：目录、原生面板及根容器背景。
- **夜色面板**（`night-surface`）：场景头、商品卡片、底部领取操作区。
- **亮层深蓝**（`night-surface-variant`）：次级按钮、图片占位、选中商品卡片。
- **奶油白**（`cream-text`）：标题和可用的次级按钮文字。
- **雾蓝白**（`mist-text`）：正文、连接信息、同步时间、一般拥有状态；禁用按钮文字另使用该色的 45% alpha。

错误与结果语义：**珊瑚粉**（`coral-error`）用于同步错误、账号错误、账本错误及退出账号文字；**薄荷绿**（`mint-success`）用于目录中已拥有状态。二者是状态色，不是新增品牌强调色。

**The Status Text Rule.** 状态始终带有文字；颜色不能单独代表已拥有、同步失败或拥有状态未确认。

## Typography

**Display Font / Body Font:** Android `sans` 系统字体；中文回退由系统提供。没有引入外部字体或像素字体依赖。

### Hierarchy

- **Headline**：原生面板标题及列表分组，使用前言 `headline`；加粗。
- **Brand title**：顶栏应用名和场景短句，使用 `brand-title`；加粗。场景短句可自然换行。
- **Title**：商品卡片标题，使用 `title`；加粗，不设置单行省略。标题的点击区域最小高度为 48dp。
- **Body**：说明、状态、时间，使用 `body`；`native_body` 增加 3dp 行间距，并由标题样式继承。没有独立固定 line-height 比例。
- **Label**：按钮与目录复选框标签，使用 `label`；按钮保持原始大小写，Fab 和 runa 不强制转大写。

系统字体缩放必须保留。原生账号页的 EditText、RadioButton 使用平台字号与控件样式，不把它们伪记为全部显式设置了 14sp。官方浏览器外壳另用 14sp 标题/状态、11sp 域名与 13sp 操作按钮，是独立的官方流程区域。

## Layout

目录框架从上到下为顶栏、三分类、可滚动 RecyclerView、底部选择摘要与两项领取动作。顶栏 Logo 为 40dp；账号和记录按钮宽度为 48dp。横向框架留白使用 12dp，面板正文内边距为 16dp。商品卡片两侧各留 4dp、下方留 12dp，文字区域使用 8dp 或 16dp 内边距。

网格根据 RecyclerView 的实际布局宽度（按 density 转成 dp）调整：小于 600dp 为一列，600dp 起为两列，1000dp 起为三列。场景头、分组标题与空状态横跨全部列。不要将这些值当作浏览器 CSS 媒体查询；初始化后仍由实际布局宽度更新列数。

场景头左侧为可换行标题与连接信息，右侧为 88dp 的装饰插画。它和更新信息、反馈、刷新、同步、继续任务及筛选项一起参与列表滚动；继续任务仅在有可恢复记录时显示。底部摘要和领取按钮位于列表外，通过列表的剩余高度分配空间。

首屏取舍遵从字号和内容：保留完整应用身份、分类、操作与状态，让商品区域滚动进入视口。在已查看的 320dp、字体 1.3 倍模拟器样例中，首屏只露出首张商品图片区域；不据此承诺所有字号和高度下首屏都有商品标题。宽屏样例能显示两列商品；该截图中的场景头已经滚出可见区域，不能据此添加“宽屏隐藏场景头”的规则。当前原生实现没有旧网页的 740px 高度断点。

**The Native Units Rule.** 布局用 dp，文字用 sp；48dp 是显式设置的按钮最小宽高及复选框/返回按钮触摸尺寸，不是要求按钮固定高 48dp。内容换行时允许高度增长。

## Elevation & Depth

页面与卡片通过深蓝底、面板、亮层深蓝形成色调层次。商品卡片没有额外投影或描边。原生 Button 的背景形状、内缩、按压反馈和焦点表现交由 `Theme.Material.NoActionBar` 的平台实现，不能宣称所有系统版本具有相同阴影或像素级外观。

没有自定义页面动效时长或缓动 token。目录显式关闭 RecyclerView 的 itemAnimator；不从这个事实推导全部 Android 控件都没有动画。

## Shapes

容器和商品图片使用直角矩形，当前布局没有设置统一圆角尺寸或图片圆角裁切。按钮保留 Android 平台背景形状；不为它们补造应用专属圆角 token。顶栏使用既有 Logo 位图，系统桌面图标的遮罩由启动器处理，两者不可混为一谈。

## Components

### Buttons

主操作醒目，辅助操作沿用深蓝。XML 按钮及 `NativeUi.kt` 动态创建的面板动作均显式设置最小触摸宽高 48dp、14sp 字号和非全大写文字。

- **Primary**：月光金底、金底深墨文字；禁用后换为亮层深蓝底，文字为雾蓝白 45% alpha。
- **Secondary**：亮层深蓝底、奶油白文字；禁用文字为雾蓝白 45% alpha。
- **Pressed / Focus**：沿用平台 Button 的状态和反馈，应用没有定义网页 hover 色或自定义焦点环。
- 底部两个动作各占一半可用宽度，间隔 8dp。无选择时“领取所选”禁用；处理中的动作显示“正在处理…”。具体可用状态由业务状态绑定，视觉文档不能覆盖领取契约。

### Cards / Containers

商品卡片默认夜色面板底，选中时改为亮层深蓝，并保留独立复选框。标题和封面可打开详情；不要将卡片任意位置点击都记作现有行为。已拥有项的复选框禁用，文字仍显示已拥有。剩余时间使用月光金，核实时间按缓存/非缓存表达为上次同步或已核实。

### Inputs / Fields

账号页地区输入为原生 EditText，最小高 48dp，最多两位字符，使用大写字母输入提示；提交时校验两位英文字母，失败使用 EditText 的 `error`。焦点线、插入光标、错误提示外观与禁用表现由平台提供，不另造填充框、圆角或动效。Fab 档位使用原生 RadioGroup / RadioButton，选项最小高 48dp。

### Navigation

目录顶部为电脑、手机、Fab 三个等宽分类按钮，并显示计数；尚无活动更新时间时计数显示“—”。当前分类用月光金，其他分类用亮层深蓝。处理期间按钮禁用并整体使用 45% alpha；这与普通按钮的禁用文字 selector 是不同实现。

账号、详情、记录使用统一原生面板顶栏和 48dp 返回按钮；正文在 ScrollView 内滚动。面板返回交回目录，应用入口负责系统返回。当前实现没有底部导航栏或平板导航 rail，文档不把它们记录成已实现组件。

### Artwork & Brand Assets

保留 `native_logo.webp`、`native_game_night.webp`、`native_mobile_gift.webp`、`native_fab_workshop.webp`。三类场景与三张既有插画一一对应；场景插画属于装饰，不作为商品封面替身。

商品目录封面区域高 156dp，使用 `centerCrop`。详情预览区域高 220dp，使用 `fitCenter`，保留完整预览。图片状态按钮覆盖图片区域：加载中显示“正在加载官方图片…”且禁用；无 URL 显示“暂无官方预览图”且禁用；加载失败显示“图片加载失败，点击重试”并启用；成功才隐藏覆盖层。封面和截图提供含商品名的内容描述。不要以一块深蓝区域或静态截图判断远程图片已成功加载。

### Official WebView Boundary

原生界面负责目录、详情、账号和记录；登录、验证码、协议、结算由官网页面负责。`OfficialBrowserController` 的外壳明确使用白底、深色标题/状态和域名，并提供返回、核实/重试、跳过与网络诊断。官网内容具有自己的样式，不能向官网套用原生夜色 token，也不能因切换到白色页面就判断原生主题失效。

视觉证据范围：已查看真实模拟器截图 `D:/Android/Builds/runa-native-verify/verification/final-320-font130/native-verification/fixture-home.png` 与 `D:/Android/Builds/runa-native-verify/verification/wide-latest/fixture-pc.png`。二者是诊断 fixture，证明所示布局与状态的呈现；不证明真实登录、订单提交或领取入库。上述其他状态由资源与绑定代码核对，不宣称均在这两张图中执行过。

`.impeccable/design.json` 的五个 HTML/CSS 组件只为设计面板提供原生组件近似预览。预览用 px 近似 dp/sp，焦点和按压使用浏览器表达；不是生产 HTML，也不能替代模拟器或真机验收。面板的八阶 OKLCH 色条从各源色合成，仅用于观察色相；应用没有相应的色阶资源，不能将色条当作生产配色、对比度验证或新增 token。生产界面仍以 XML、Kotlin 及平台状态为准。

## Do's and Don'ts

### Do:
- **Do** 保留 runa周免口袋名称、既有 Logo、三张场景插画和月夜配色。
- **Do** 使用 dp/sp、系统字体和可随内容增长的高度，检查大字号下的实际可见范围。
- **Do** 让状态文字、缓存标记与时间跟随真实数据，配合颜色帮助扫描。
- **Do** 根据原生列表实际宽度调整列数，保留分组和反馈的全宽布局。
- **Do** 为图片加载、失败重试和缺失提供不同的可读状态。

### Don't:
- **Don't** 新增外部字体依赖，或用像素字体替换系统正文。
- **Don't** 把未知拥有状态写成未拥有，或把点击完成显示成已确认入库。
- **Don't** 将平台按钮外观、预览 CSS 或旧网页高度断点写成原生固定设计值。
- **Don't** 用品牌插画冒充商品官方封面，或将图片占位当成已加载证据。
- **Don't** 向官方 WebView 强行套用夜色主题，或把诊断截图当作真实交易验证。
