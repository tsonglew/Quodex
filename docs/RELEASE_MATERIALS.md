# Quodex 发布素材与帮助

本文准备产品身份、简介、视觉素材和使用帮助，并区分公开安装包与当前工作区。公开渠道为 GitHub Releases APK。截至 2026-10-23 已核实的最新预发布是 [alpha21 · build-39890074165-2](https://github.com/tsonglew/Quodex/releases/tag/build-39890074165-2)（03:04 UTC）；本文新增的深海青界面来自其后的本地 debug 构建，尚未公开发布。固定签名、覆盖升级与真实账号接入范围继续按发布 checklist 验收。

## 产品身份与简介

名称：Quodex。

身份说明：Quodex 是独立第三方 Android 工具，与 OpenAI 无隶属关系。Codex 为所兼容服务的名称，不表示官方授权或合作。

简短介绍：在 Android 手机和桌面小组件查看 Codex 额度、重置时间与 Token 使用趋势。

完整介绍：Quodex 提供单账号额度速览、剩余／已用切换、近 7／30 天 Token 日用量图表和逐日明细。流线 Q 标志与深海青浅深色主题贯穿 App 和桌面组件。标准与独立 2×1 额度组件共享成功快照；手动刷新有进度反馈，后台刷新受 Android 网络、省电和厂商限制。设置页提供分品牌后台更新引导。示例模式由用户主动选择，明确显示示例数据。

当前文案描述 `0.1.0-alpha21` 工作区。Token 兼容接口仍待真实账号验收；缺失记录与实际零值分别展示，失败保留缓存，不提供输入／输出／缓存拆分或费用估算。本地 debug 包和 API 31 示例截图不构成正式 Release。

当前为实验版本。一次连接成功不代表续期、全部账号／工作区或长期后台运行均已验证。公开接入条件见 [接入证据与边界](CODEX_INTEGRATION.md)，对外描述采用 [支持范围与已知限制](SUPPORT.md)，明确已验证范围及未完成验收。

## 视觉素材

- 品牌素材：[流线 Q、字标与配色规范](BRANDING.md)；默认 App 图标为深墨底 `#112B30` 配亮青 Q `#5CD8CD`，浅色背景标志使用深海青 `#007C83`。不使用 OpenAI 官方标志。
- 可交付 SVG：[浅底横向组合](assets/brand/quodex-horizontal-color.svg)、[深底横向组合](assets/brand/quodex-horizontal-on-dark.svg)、[默认 App 图标](assets/brand/quodex-app-icon.svg)；Android 工作区使用同一轮廓与配色，资源路径见品牌说明。
- 当前真实页面与组件图片：[截图索引](SCREENSHOTS.md)。2026-10-23 在本地 API 31 截取，包含额度总览、Token 柱图、组件预览、设置的浅深色，以及真实标准／紧凑额度组件；均为示例数据。
- 历史宣传图：[quodex-preview.png](assets/quodex-preview.png)，[原制作说明](assets/preview-generation.md)。该合成图属于 alpha03 蓝色主题，保留来源，不再用于当前 README 或深海青版的首屏介绍。
- 历史无障碍验证截图：[标准组件焦点](screenshots/talkback-standard-focus.png)、[紧凑组件更新](screenshots/talkback-slim-updated.png)，来源见 [alpha11](VALIDATION_ALPHA11.md)。这些图片保留当时界面，不能代替本轮验证。

准备 GitHub Release 说明时，可使用本轮品牌素材与真实示例截图，但必须随实际发布的 APK 核对版本、签名和来源。当前资料更新本身不会上传 APK、创建 Release 或证明公开构建已包含这些界面。

## 使用帮助

1. 首次打开，选择连接账号并按 App 引导在服务授权页面完成登录；示例模式仅用于预览界面。
2. 在额度页查看各周期及成功时间，向下浏览 Token 近 7／30 天图表并打开逐日明细。没有返回周期或用量时，页面会说明数据不可用，不能据此判断额度无限或用量为零。
3. 从桌面的组件选择器添加“Codex 额度”或“Codex 额度 · 2×1”；实际格子占用由桌面 Launcher 决定。多个实例共享数据，可独立配置主题和剩余／已用显示。
4. 点击组件的刷新入口获取新数据；刷新期间显示进度。检查成功时间是否变化，旧缓存不代表刚刚请求成功。
5. 自动更新延迟时，在设置页打开后台更新引导，按实际品牌核对自启动、后台省电和最近任务锁定。菜单差异及限制见 [后台引导](BACKGROUND_GUIDE.md)。
6. 网络恢复后，等待中的同步可继续；服务限流时需等待 Retry-After，反复点击不会绕过限流。持续失败请参考 [故障提示与兼容策略](COMPATIBILITY_POLICY.md)。
7. 系统强制停止后，需要重新打开 App 恢复；后台任务不能保证在强停期间运行。普通清理后台与系统强停要分别验证。
8. 退出清除本机凭据及账号缓存，并让组件回到未连接状态。远程授权不会因此自动撤销；详见 [隐私说明](PRIVACY.md)。

报告问题时可提供 App 版本、手机型号／Android 版本、组件类型、最后成功时间和操作步骤。不要发送 token、设备码或登录凭据；诊断导出仍应自行检查是否含个人信息。


## GitHub APK 安装与更新

1. 从 [Quodex Releases](https://github.com/tsonglew/Quodex/releases) 选择版本，实验版本带 Pre-release 标记；只有附件中实际存在的 APK 才可下载，本文不表示已有正式签名版本。
2. 下载 Quodex-版本.apk、SHA256SUMS 和 provenance.json；在电脑上可运行 `shasum -a 256 文件名.apk`，核对对应 SHA-256。哈希用于检查下载完整性，签名指纹与源提交记录在 provenance.json。
3. 用手机打开 APK，按 Android 提示允许当前下载／文件应用安装来自此来源的应用，再确认安装。安装后打开 Quodex；不需要 root。
4. 当前无自动安装功能。更新前查看版本说明中的已知限制；发布异常可先保留现用版本并在 Issues 反馈，不移动或替换已公开版本 tag。

排查问题时，从设置 → 问题排查预览诊断报告，确认后再主动分享。报告含最近 200 条事件及 7 天统计；详细字段和 48 小时／7 天观察步骤见 [支持说明](SUPPORT.md)。
