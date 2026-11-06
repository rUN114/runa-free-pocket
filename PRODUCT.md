# runa周免口袋

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

使用安卓手机领取 Epic 电脑游戏、Android 游戏及 Fab 限免素材的个人用户，可能拥有多个账号。用户希望直观看到封面、当前和即将推出内容及真实拥有状态，并一键处理未拥有的免费项目。

## Product Purpose

原生界面查看限免、详情、账号和领取记录；官方 WebView 完成认证、验证码、协议与结算。只有明确零元订单可自动提交，以官方库存/拥有凭证确认成功。

## Operating Context

最低 Android 8.0。中文优先；大陆网络可能超时或受验证限制。保留切换与退出账号，Epic/Fab 共用网页登录状态。未开始、已拥有项目不进入领取队列，无法核实状态不能冒充未拥有。可能提交过的订单只核实，不清保护重下单。

## Constraints & Evidence

用户已选择“原生界面＋结构重构，优先小体积”。采用 Kotlin + Android Views/XML；已有 4087d48 版本真实自动领取 Galaxy Kart 的记录。旧源码、正式签名、图标、已保存账号加密数据及领取账本须保留。账号原件、Cookie和签名文件不在源码中。

## Brand Commitments

名称为 runa周免口袋。保留用户提供的白发角色、黑猫、R发夹与月夜像素插画，沿用 docs/BRANDING.md 中已确认的资源和色彩。原生控件布局需尊重 Android 返回、触摸、字体和系统边距习惯。

## Product Principles

- 领取结果来自官方证据，不来自点击行为。
- 已有身份/状态/预约保护必须在重构中延续。
- 界面常用动作明显，错误可定位并有明确恢复方式。
- 依赖有实际用途，体积以正式构建实测。
