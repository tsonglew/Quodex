# alpha03 独立 2×1 桌面组件验证

版本：`0.1.0-alpha03`，versionCode 3，Android 10+。验证日期：2026-10-09。

实现与测试宿主提交：`5553ae71d54f3db0ccb11ad9af8f57a127d9c9ba`。[CI 37953081016](https://github.com/tsonglew/Quodex/actions/runs/37953081016) 全部成功。

新增独立“Codex 额度 · 2×1”系统入口与 App 内“添加 2×1 紧凑组件”按钮。单行组件显示主要 Codex 窗口或账号状态、已用／剩余标记、成功更新时间和刷新入口，70dp 高时显示分段条。原有标准组件继续保留。

| 检查 | 结果 |
| --- | --- |
| 单元测试 | 36 项通过，0 失败 |
| 编译与 lint | App、测试 APK、lint 全部通过 |
| Android 35 设备测试 | 9 项通过 |
| 2×1 元数据与尺寸 | 独立 provider 的目标网格 2×1；原生宿主测试 130×70、140×70、280×70、260×50 dp |
| 主题与数值 | 浅深色、剩余 62%／已用 38%；紧凑组件仅显示主要窗口 |
| 额度状态 | 当前可用、未提供周期额度、仅附加审查额度、未知用量、使用受限；不虚构百分比或将附加额度当作主 Codex 额度 |
| 文字可见性 | 所有可见文字完整显示且无省略；更新时间保留完整月日与时分 |
| 混合实例 | 同一个原生宿主同时绑定标准与单行入口，共享快照更新，已用／剩余与主题独立保存 |
| 退出 | 单行组件切换为未连接与连接提示 |

首次设备回归发现两个 ActivityScenario 宿主同时启动会销毁前一个 Activity，修正为同一宿主承载两个真实组件；最终结果包含此修正。标准组件与 alpha02 的额度状态回归也通过。

实际格子占用和比例由 Launcher 决定；尺寸参数参考 [Android 官方布局说明](https://developer.android.com/develop/ui/views/appwidgets/layouts)。原生宿主验证不能代替系统选择器、真实 Launcher、旧 Android 版本和 OEM 验收；点击刷新、重启／进程恢复与长时间后台仍继续按 checklist 验证。

## 安装包

[GitHub 预发布下载](https://github.com/tsonglew/Quodex/releases/tag/v0.1.0-alpha03)。文件：`QuotaPal-0.1.0-alpha03-debug.apk`，65,241,216 bytes。ZIP CRC、版本字符串与新组件类已校验。

SHA-256：

```text
81c4d8c314075731f07b3221509cdf0d1dc34da6191799ae97b6313f418d8884
```

仍为 CI debug 签名自用测试包。若与旧包签名不同，无法覆盖安装；卸载旧版会清除本机连接和设置。稳定签名与升级迁移仍由 M5 跟踪。
