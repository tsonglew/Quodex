# Quodex 品牌与配色

2026-10-23 当前工作区采用已确认的圆润流线 Q：环形主体、双切口与短尾保持定稿轮廓。Quodex 是独立第三方工具；标志与字标用于本项目，不使用 OpenAI 官方标志或暗示官方授权。

## 颜色角色

| 用途 | 颜色 |
| --- | --- |
| 浅底主标志、浅色主题主色 | 深海青 `#007C83` |
| 字标、默认图标背景、深色卡片 | 深墨色 `#112B30` |
| 默认图标 Q、深色主题主色 | 亮青色 `#5CD8CD` |
| 浅色页面背景 | 浅青白 `#EEF8F7` |
| 深色页面背景 | `#0B1C20` |
| 浅色卡片、反白标志 | `#FFFFFF` |

App 和 Glance 组件共享 `Theme.kt` 的浅深色角色，Token 图表、额度条和选择控件使用同一主色。错误与警告保留独立语义色。亮青标志配深色背景；浅色背景使用深海青。标志采用纯色，不增加描边、渐变或填满切口。

## 资产来源与使用

仓库可直接引用的定稿 SVG 位于 [docs/assets/brand/](assets/brand/)，从本地完整套件 `design/logo/flow-q/color-v2/kit/` 原样复制。完整套件沿用 `approved/` 的已选路径，只调整颜色；它的原始指南记录了当时仅处理 Logo／图标的阶段，本次 App 主题同步范围见 [UI/UX](UI_UX.md)。

| 场景 | 资产 |
| --- | --- |
| 浅底横向组合 | [quodex-horizontal-color.svg](assets/brand/quodex-horizontal-color.svg) |
| 深底横向组合 | [quodex-horizontal-on-dark.svg](assets/brand/quodex-horizontal-on-dark.svg)，透明底，需配深色背景 |
| 默认 App 图标 | [quodex-app-icon.svg](assets/brand/quodex-app-icon.svg)，深墨底配亮青 Q |
| 独立符号 | [浅底常规](assets/brand/quodex-symbol.svg)／[深底常规](assets/brand/quodex-symbol-on-dark.svg)；16–24 px 使用 [浅底小尺寸](assets/brand/quodex-symbol-small.svg)／[深底小尺寸](assets/brand/quodex-symbol-small-on-dark.svg) |
| 网页图标 | [favicon SVG](assets/brand/quodex-favicon.svg)、[maskable SVG](assets/brand/quodex-maskable.svg) |

Android 工作区资源为 `app/src/main/res/drawable/ic_quodex.xml`、`ic_quodex_foreground.xml` 与 `ic_quodex_monochrome.xml`；这些路径说明接入位置，公开 APK 是否包含新图标仍以发布记录为准。

SVG 已将符号与字标转成填充路径，无字体依赖；保留画布留白、比例和字距。横向组合建议宽度至少 220 px。Android adaptive icon 与网页 maskable 版本使用各自安全区，不互换裁切方式。API 33 monochrome 本轮为资源编译检查，实际 App 显示截图来自 API 31。

## 当前显示与发布边界

[截图索引](SCREENSHOTS.md)提供 2026-10-23 的深海青真实界面与原生组件截图、测试轮次和 APK 哈希。所有展示均使用示例数据。本轮工作区的本地 debug 构建、lint 和定向视觉检查已完成；历史 CI、公开 Release 和真实账号验收分别记录，不将素材更新当作发布完成。
