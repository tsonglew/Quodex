# Android 原生截图

来自 [已通过的 Android 35 设备测试](https://github.com/tsonglew/Quodex/actions/runs/37936883382)，并经视觉检查。所有额度均为示例数据。App 由 Compose 测试捕获，组件由真实 AppWidgetHost 渲染后使用 PixelCopy 捕获。

下方是 QuotaPal 时期的历史验收截图，保留原始画面与来源；当前品牌名称为 Quodex。最新品牌预览见 README。

## 额度页

| 浅色 | 深色 |
| --- | --- |
| <img src="screenshots/quota-light.png" width="300" alt="浅色额度页" /> | <img src="screenshots/quota-dark.png" width="300" alt="深色额度页" /> |

## 桌面小组件

140×150 dp 紧凑版：

<img src="screenshots/widget-140x150-light.png" width="220" alt="浅色紧凑组件" />

280×150 dp 宽版：

<img src="screenshots/widget-280x150-dark.png" width="440" alt="深色宽版组件" />

140×230 dp 纵向版：

<img src="screenshots/widget-140x230-light.png" width="220" alt="浅色纵向组件" />

## 引导与退出

| 小组件引导 | 退出后的未连接页 |
| --- | --- |
| <img src="screenshots/widgets-light.png" width="300" alt="小组件引导" /> | <img src="screenshots/welcome-dark.png" width="300" alt="退出后的欢迎页" /> |

## alpha02 可选额度状态

来自 [已通过的 alpha02 Android 35 测试](https://github.com/tsonglew/Quodex/actions/runs/37946436238)。均为合成示例数据；页面图片为对应 Compose 信息区域，组件图片来自实际原生宿主。

| 仅周额度 | 无周期窗口且可用 | 未知用量 |
| --- | --- | --- |
| <img src="screenshots/quota-weekly-only.png" width="300" alt="说明未提供短周期额度并保留周额度" /> | <img src="screenshots/quota-no-windows.png" width="300" alt="当前可用说明" /> | <img src="screenshots/quota-unknown-usage.png" width="300" alt="未知用量显示说明而非空进度条" /> |

| 周额度紧凑组件 | 无周期窗口组件 | 受限状态组件 |
| --- | --- | --- |
| <img src="screenshots/widget-weekly-only-140x150.png" width="220" alt="周额度提示和完整更新时间" /> | <img src="screenshots/widget-no-windows-140x150.png" width="220" alt="明确显示当前可用" /> | <img src="screenshots/widget-restricted-140x150.png" width="220" alt="明确显示当前使用受限" /> |

## alpha03 独立 2×1 组件

来自 [已通过的 alpha03 Android 35 测试](https://github.com/tsonglew/Quodex/actions/runs/37953081016)，经视觉检查。图片由真实 AppWidgetHost 渲染并原样捕获，使用合成示例数据。系统入口为“Codex 额度 · 2×1”，实际格子占用由 Launcher 决定。

| 130×70 dp 浅色 | 140×70 dp 深色 |
| --- | --- |
| <img src="screenshots/widget-slim-130x70-light.png" width="260" alt="2×1 浅色周额度组件" /> | <img src="screenshots/widget-slim-140x70-dark.png" width="280" alt="2×1 深色周额度组件" /> |

260×50 dp 较矮布局：

<img src="screenshots/widget-slim-260x50-dark.png" width="440" alt="较矮单行组件保留百分比和完整更新时间" />

| 无周期窗口且可用 | 未知用量 | 使用受限 |
| --- | --- | --- |
| <img src="screenshots/widget-slim-available-140x70.png" width="280" alt="紧凑组件显示当前可用" /> | <img src="screenshots/widget-slim-unknown-130x70.png" width="260" alt="紧凑组件显示暂不可用" /> | <img src="screenshots/widget-slim-restricted-140x70.png" width="280" alt="紧凑组件显示当前使用受限" /> |
