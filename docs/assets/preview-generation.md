# README 视觉素材来源

## 当前展示（2026-10-23）

README 已改用 [深海青品牌 SVG](brand/quodex-horizontal-color.svg)和 `docs/screenshots/deep-teal-api31/` 中的真实浅深色额度页截图。页面图片直接来自本地 API 31 模拟器，使用当前工作区 debug APK 和离线示例数据；截图原样复制，不经过合成、裁切或重绘。完整来源、APK 哈希及验证范围见 [原生截图索引](../SCREENSHOTS.md)，Logo 的深浅背景规则见 [品牌规范](../BRANDING.md)。

本次没有生成或修改 `quodex-preview.png`。下方保留 2026-10-10 的原始制作记录，其 alpha03 蓝色界面属于历史展示，不代表当前深海青主题，也不证明当前版本已经发布。

## 历史合成图来源与提示词

生成日期：2026-10-10。使用 imagegen 技能与内置 `image_gen` 工具，基于原生截图合成展示图；未使用 CLI fallback。

输出：[quodex-preview.png](quodex-preview.png)，1586×992 px。图中数据为示例数据；此展示图不代替 [原生截图](../SCREENSHOTS.md) 与设备验证记录。

输入图片依次为：

1. `docs/screenshots/quota-light.png`
2. `docs/screenshots/quota-dark.png`
3. `docs/screenshots/widget-140x150-light.png`
4. `docs/screenshots/widget-slim-140x70-dark.png`
5. `docs/screenshots/widget-280x150-dark.png`

最终提示词：

```text
Use case: compositing / product-mockup. Create one polished wide landscape README product preview image for the actual Android app Quodex, preferably 2400×1500. Input images are supporting compositing inserts, not inspiration for invented UI: image 1 actual light app screenshot, image 2 actual dark app screenshot, image 3 actual compact standard light widget, image 4 actual 2×1 dark widget, image 5 actual wide dark widget. Preserve these UI screens and widgets with their exact text, percentages, blue segmented bars, Chinese example-data labels and proportions, as faithfully as possible. Do not redesign the UI. Composition: calm premium light neutral gray background with subtle blue illumination and ample whitespace, editorial product presentation. Top left clean large title exactly 'Quodex', underneath exactly 'Codex 额度 · Android 桌面小组件'. Beneath the header, show two straight-on slim generic Android phones side by side occupying the left 55% of composition, showing the complete light and dark actual app screenshots respectively inside minimal graphite frames with subtle shadows. Preserve screenshot content; no perspectives, no skew. Small labels '浅色' and '深色' below the phones. On the right 40%, an organized widget showcase on a subtle rounded desktop background panel: heading '桌面小组件'; actual 2×1 dark widget at top with label '2×1 紧凑组件', actual light standard widget in middle with label '标准组件', actual wide dark widget at bottom with label '宽版组件'. Widgets should be sufficiently large for their 62% and 36% numbers and major Chinese content to be readable; preserve real proportions. Remove the gray screenshot margins around the widgets as part of compositing so the rounded card edges look native within the panel. The 2×1 screenshot is roughly twice as wide as tall: preserve this shape. Very small but readable footer exactly '界面与小组件使用示例数据 · alpha03'. High quality precise typography. No invented features, no App Store badges, no Apple hardware, no extra logos or decorative graphs, no watermark. Overall visually faithful to the supplied real app, beautiful and useful as a GitHub README hero preview.
```
