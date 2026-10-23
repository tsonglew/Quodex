# Quodex 发布素材与帮助

本文准备产品身份、简介、现有视觉素材和使用帮助，不表示已经公开发布。公开渠道已确定为 GitHub Releases APK；固定签名与真实账号接入范围仍由发布 checklist 单独验收。

## 产品身份与简介

名称：Quodex。

身份说明：Quodex 是独立第三方 Android 工具，与 OpenAI 无隶属关系。Codex 为所兼容服务的名称，不表示官方授权或合作。

简短介绍：在 Android 手机和桌面小组件查看 Codex 额度与最近成功更新时间。

完整介绍：Quodex 提供单账号额度速览、剩余／已用切换、浅深色主题和两种桌面组件入口。App 与组件共享成功快照；手动刷新有进度反馈，后台刷新受 Android 网络、省电和厂商限制。设置页提供分品牌后台更新引导。示例模式由用户主动选择，明确显示示例数据。

当前为实验版本。一次连接成功不代表续期、全部账号／工作区或长期后台运行均已验证。公开接入条件见 [接入证据与边界](CODEX_INTEGRATION.md)，对外描述采用 [支持范围与已知限制](SUPPORT.md)，明确已验证范围及未完成验收。

## 视觉素材

- App 图标源文件：[ic_quodex.xml](../app/src/main/res/drawable/ic_quodex.xml)。使用项目自身图形，不使用 OpenAI 官方标志。
- 原生页面及组件截图：[截图索引](SCREENSHOTS.md)。索引分别记录来源版本与测试记录；图片均使用示例或合成数据，不含真实账号。
- 宣传预览：[quodex-preview.png](assets/quodex-preview.png)，[制作说明](assets/preview-generation.md)。这是基于原生截图合成的展示图，不替代实际设备截图。
- 无障碍验证截图：[标准组件焦点](screenshots/talkback-standard-focus.png)、[紧凑组件更新](screenshots/talkback-slim-updated.png)，来源见 [alpha11](VALIDATION_ALPHA11.md)。

GitHub Release 说明使用现有图标与示例截图链接；图像来源与版本见索引，不把宣传合成图描述为未经处理的真机画面。

## 使用帮助

1. 首次打开，选择连接账号并按 App 引导在服务授权页面完成登录；示例模式仅用于预览界面。
2. 在额度页查看各周期及成功时间。没有返回周期或用量时，页面会说明数据不可用，不能据此判断额度无限。
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
4. Quodex 使用新的 Android applicationId `com.tsonglew.quodex`。旧版 QuotaPal 不会原地升级；安装 Quodex 后需要重新连接账号，旧数据不会自动迁移。旧版更新检查使用旧仓库 API 地址，无法跟随 GitHub 的仓库改名跳转，请从新的 Quodex Releases 页面手动下载。两者可以暂时并存，确认新版本正常后再手动卸载旧版。之后同包名、兼容正式签名且 versionCode 更高的 Quodex 版本可覆盖安装并保留数据。不要随意安装旧版本；签名不兼容或降级时由系统拒绝。CI debug 版切换正式签名通常需先卸载，再安装并重新连接。
5. 当前无自动安装功能。更新前查看版本说明中的已知限制；发布异常可先保留现用版本并在 Issues 反馈，不移动或替换已公开版本 tag。

排查问题时，从设置 → 问题排查预览诊断报告，确认后再主动分享。报告含最近 200 条事件及 7 天统计；详细字段和 48 小时／7 天观察步骤见 [支持说明](SUPPORT.md)。
