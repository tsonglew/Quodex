# 小组件选择器预览

两个 provider 的 `previewImage` 使用真实 Glance 组件截图，避免桌面选择器回退显示应用图标。图片放在 `drawable-nodpi`，由桌面选择器保持比例缩放；数据来自示例模式。

当前预览来自 2026-10-23 深海青配色工作区构建，在本地 Android 12 / API 31（Pixel 6，420 dpi）的只读临时模拟器中，通过现有 `WidgetRenderTest` 的两项测试生成。真实宿主通过 PixelCopy 截图，已实际检查浅色与深色组件的标题、额度、进度条和示例提示。两项测试均通过，31.704 秒完成，报告为 `OK (2 tests)`，最终 instrumentation code 为 -1。原始日志保存在本地 `artifacts/theme/deep-teal/widget-tests-api31/instrumentation.txt`；测试轮次与完整截图见 [截图索引](SCREENSHOTS.md)。

| 资源 | 真实组件截图 |
| --- | --- |
| `quota_widget_preview.png` | [widget-140x150-light.png](screenshots/deep-teal-api31/widget-140x150-light.png) |
| `slim_quota_widget_preview.png` | [widget-slim-130x70-light.png](screenshots/deep-teal-api31/widget-slim-130x70-light.png) |

截图方法为 `WidgetRenderTest#realWidgetsRenderCompactWideTallAndClearOnLogout` 和 `WidgetRenderTest#slimWidgetsRenderOneRowQuotaStatesAndIndependentSettings`。本轮生成截图的主 APK SHA-256 为 `b7908a9b9e7010e005861c07796fb4ee940227d8c78758dd99cdf258b5a4ce88`，测试 APK SHA-256 为 `636dce5af93764c93b130bca12975270925f643237696d89018fe988ef6f15be`；最终本地 APK 已包含这两张更新后的预览，哈希见截图索引。本轮工作区资源更新不表示相同预览已进入公开 Release。

图片直接复制，未用产品 Logo 或生成式示意图替代。修改组件布局或配色后，重新运行上述 `WidgetRenderTest` 方法，从通过测试的产物中更新这两张截图，并检查标准组件和 2×1 组件的标题、额度、进度条及示例数据提示。预览展示浅色默认尺寸，实际组件会按用户选择的主题与尺寸渲染。
