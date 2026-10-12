# QuotaPal

面向 Android 的 Codex 额度监控 App，参考 Nowdex 的额度速览与桌面小组件体验。QuotaPal 是独立第三方工具，与 OpenAI 无隶属关系。首版支持单 Codex 账号，先自用验证，再公开发布。

![QuotaPal 浅色与深色界面，以及 2×1、标准和宽版桌面小组件预览](docs/assets/quotapal-preview.png)

基于原生界面截图合成的展示效果图，额度均为示例数据。[查看原生截图](docs/SCREENSHOTS.md)。

当前开发版本为 `0.1.0-alpha19`。2×1 组件改为单套可伸缩布局，以排查桌面对多尺寸布局的点击分发兼容性，尚未确认解决小米真机问题。alpha18 小组件为标题、额度和状态内容分别绑定打开应用动作，使用显式主入口唤起应用，并记录组件打开应用的诊断事件；用户已反馈 alpha18 的 2×1 组件主体和刷新按钮仍无响应，问题继续排查中。alpha17 已新增诊断报告的系统后台受限标记，帮助排查后台刷新延迟。既有 alpha16 的 [PR #16 六组 CI](https://github.com/tsonglew/QuotaPal/actions/runs/38037325156) 已通过，覆盖 API 29／31／35／36／37.0／37.2。已提供本机诊断报告、最近 7 天的请求与刷新统计、后台任务状态，以及分品牌后台引导和小组件即时手动刷新。alpha16 修正诊断弹窗的主线程约束，固定签名覆盖升级已通过，验证记录见 [alpha16](docs/VALIDATION_ALPHA16.md)。真实账号续期、OEM 后台恢复和长期自用仍待验收。

已核验版本：[0.1.0-alpha17 预发布](https://github.com/tsonglew/QuotaPal/releases/tag/v0.1.0-alpha17)。

合并到 master 后自动运行完整 CI，生成固定签名 APK 并上传 [GitHub Releases](https://github.com/tsonglew/QuotaPal/releases)。每次构建以 `build-<runId>-<attempt>` 创建独立预发布，附 APK、SHA256SUMS 和来源信息，保留历史包；Actions 中的 `android-signed-apk` 同时保留 14 天。正式版本仍可按版本 tag 发布。

- [开发计划](docs/DEVELOPMENT_PLAN.md)
- [开发与验收 checklist](docs/DEVELOPMENT_CHECKLIST.md)
- [GitHub milestones](https://github.com/tsonglew/QuotaPal/milestones)
- [实时开发进度](docs/PROGRESS.md)
- [CI/CD、预览 Deployments 与签名发布](docs/CI_CD.md)
- [隐私说明与数据流](docs/PRIVACY.md)
- [后台更新引导](docs/BACKGROUND_GUIDE.md)
- [小组件刷新修复验证](docs/VALIDATION_ALPHA06.md)
- [Codex 接入证据与边界](docs/CODEX_INTEGRATION.md)
- [接口兼容与故障维护策略](docs/COMPATIBILITY_POLICY.md)
- [界面规格](docs/UI_SPEC.md)
- [测试与自用验收](docs/TESTING.md)
- [历史修复 PR](https://github.com/tsonglew/QuotaPal/pull/8)
- [alpha03 验证记录与 2×1 安装包](docs/VALIDATION_ALPHA03.md)
- [alpha02 验证记录](docs/VALIDATION_ALPHA02.md)
- [首版验证与 APK 校验和](docs/VALIDATION.md)
- [原生页面和组件截图](docs/SCREENSHOTS.md)
- [产品简介、发布素材与使用帮助](docs/RELEASE_MATERIALS.md)

Android 10+，Kotlin 2.1.21、Compose、Glance、Room、DataStore 和 WorkManager。固定构建链为 AGP 8.10.1、Gradle 8.11.1、JDK 17，compileSdk／targetSdk 36。

已有 Android SDK 与 JDK 17 时执行 `./gradlew testDebugUnitTest lintDebug assembleDebug`。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。有设备或模拟器时执行 `./gradlew connectedDebugAndroidTest`。GitHub Actions 会保存 APK、测试报告及原生截图。

设备登录由用户在 OpenAI 页面完成，QuotaPal 不收集密码。示例模式须主动选择并明确标注，不执行额度请求。凭据通过 Android Keystore 加密保存在本机；退出清理本机连接，远程授权不会因此自动撤销。

当前账号覆盖、刷新限制和未完成验收见 [支持范围与已知限制](docs/SUPPORT.md)。

普通版与横条版桌面小组件都支持手动刷新：点击 `↻` 原地刷新额度，刷新期间显示 `…`，结果同步到其他已添加的小组件。点击小组件的标题、额度或其他主体区域会打开 QuotaPal；未连接账号时，刷新入口也会打开应用以完成连接。示例模式不请求真实额度。
