# Android UI/UX 优化依据

## 当前深海青主题（2026-10-23）

沿用已确认的流线 Q 标志，将 App 页面、标准／紧凑额度组件和 Token 趋势组件统一到深海青色系。浅色主色 `#007C83` 配浅青白背景，深色主色 `#5CD8CD` 配深墨表面；进度条、柱状图、7／30 天筛选、外观选项和开关使用对应主题角色。错误与警告保持独立语义色，布局和数据口径沿用此前实现。[品牌与素材来源](BRANDING.md)记录 Logo 规则。

从当前 `Theme.kt` 的实际色值，按 WCAG sRGB 相对亮度计算 9 组前景／背景配对：主色及其容器、辅助色及其容器、警告色及其容器、背景、表面、表面变体。浅色最低 **4.99:1**（白色文字／深海青主色），深色最低 **6.39:1**（表面变体上的次要文字）。这是颜色角色检查，不涵盖透明度叠加、禁用状态或每个实际文字像素，也不等于整个 App 的无障碍认证。

本地 `assembleDebug`、`assembleDebugAndroidTest` 和 `lintDebug` 成功，最终 lint 为 0 错误、54 警告。API 31 的两项既有 `WidgetRenderTest` 通过；最终 APK 另截取并逐张查看额度、组件预览、设置及 Token 图表的浅深色共八张全屏图。真实组件预览图已同步到系统选择器资源，未制作新的合成展示图。截图、APK 哈希和测试轮次见 [截图索引](SCREENSHOTS.md)。

本轮验证使用断网、只读临时模拟器与示例数据，未重跑此前所有布局／TalkBack／OEM 专项，也未验证真实账号统计或发布签名升级。当前工作区显示效果和历史 CI 结果分别记录。

## 2026-10-22 布局与交互改进

2026-10-22 核对 Android Developers 官方指南，并结合 Quodex 的原生 Compose 页面落实以下改动。保留现有三入口和额度／Token 数据口径。

| 官方建议 | 原页面的问题 | 本次实现 |
| --- | --- | --- |
| [可访问性设计](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility)：正文不小于 12sp，文字与背景对比度至少 4.5:1，点击区域至少 48dp | 日期、图例等使用 10–11sp；浅色主按钮文字对比度不足；一些主题角色沿用默认紫色 | App 说明文字提升至至少 12sp；补齐浅深色主题的主色、辅助色、警告和容器颜色；新开关行最小 56dp |
| [Compose 默认可访问性](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)：选择控件将点击行为提升到父行 | 必须点中右侧小开关，标签与开关分离 | 显示偏好、应用更新、小组件配置共用 `PreferenceToggle`；整行响应，子 Switch 不重复接收点击 |
| [语义](https://developer.android.com/develop/ui/compose/accessibility/semantics)：描述控件角色、状态和内容 | 装饰标识被朗读；部分内容缺少分组 | 开关具有标签、说明、Switch 角色和状态；标题支持标题导航，趋势包含总量、缺失和实际零值说明；逐日明细可读取精确数值 |
| [不同显示尺寸](https://developer.android.com/develop/ui/compose/layouts/adaptive/support-different-display-sizes)：按实际可用空间布局 | 固定横排的选项和百分比挤压大字体；宽屏卡片过宽 | 选项和日期自动换行，额度信息按宽度和字号上下排列；主内容居中且最大宽 640dp，小组件配置页最大宽 600dp |
| [自适应导航](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation)：紧凑窗口底部导航，较宽窗口侧边导航 | 所有尺寸都采用底部栏 | 复用 Material 3 组件，根据窗口约束选择底部栏或侧边栏；紧凑高度保留底部栏 |
| [保存界面状态](https://developer.android.com/develop/ui/compose/state-saving)：保存滚动位置和用户选择 | 切换标签后回到顶部、趋势重置为 7 天 | 每个标签使用独立可保存状态，保留滚动位置和趋势周期 |

首页将品牌与刷新收进固定顶部栏，减少顶部重复留白；页面标题使用“额度概览”“桌面小组件”“设置”。刷新不再随内容滚出屏幕。额度状态已有文字说明，移除无法单独解释含义的颜色圆点。以上属于结合本应用的设计选择，并非官方要求的唯一布局。

### 当时的验证范围

- 当时蓝色主题的 9 组前景背景配对计算值为浅色最低 5.70:1、深色最低 6.85:1。这是历史颜色检查记录，当前深海青数值见上文；均不代表整个应用已通过无障碍认证。
- 原生回归覆盖每个标签独立滚动恢复、30 天周期保持、刷新可达、整行开关的语义与真实文字区域触摸。
- 在 API 31 模拟器使用真实系统 200% 字体配置和 320dp 可用宽度，检查原生文字布局是否截断、主要操作是否可达，并导出浅深色截图。
- 运行结果与截图保存在本地 `artifacts/ui-ux-validation/`，最终结果见 [测试记录](TESTING.md)。模拟器验证不代替真实 TalkBack 人工体验、OEM 设备和各 Android 版本的完整验收。
