# alpha04 验证记录

日期：2026-10-10。版本 `0.1.0-alpha04`／versionCode 4，分支 `codex/stability-matrix`，尚未合并。

## 已验证

- [GitHub CI 37978463336](https://github.com/tsonglew/Quodex/actions/runs/37978463336)，提交 `d12b6f65c96819a55880f9b9bdc5dc0502552463`：交付配置检查、40 项 JVM 测试、lint、debug APK、API 29／31／35／36 设备测试及 `Android CI gate` 全部通过。
- 本地 Android 35 标准 AOSP：直接运行 instrumentation，10 项测试全部通过，包括 WorkManager 唯一周期任务、页面、加密存储及组件原生渲染。保存 53 张截图，抽查紧凑和标准深色组件，数字与更新时间完整可见。
- 本地 `lintRelease assembleRelease` 成功，R8 混淆开启。实际 manifest 为 versionCode 4、禁止备份、禁止明文网络，未开启 debuggable。
- 实际 release APK 使用一次性测试密钥执行 zipalign、apksigner 签名与证书指纹验证成功。GitHub 发布命令使用替身，未创建线上 Release；测试密钥不用于正式发布。
- 交付脚本 11 项测试通过：6 项产物来源及完整性测试，5 项发布控制测试。actionlint 与 ShellCheck 通过。
- 用户报告小米 17 Ultra／Android 17 已成功连接真实 Codex 账号；不包含凭据或账号原始数据。

## 验证边界

ATD 镜像的 3 项组件截图测试遇到 PixelCopy 无数据，改用标准 AOSP 后全部通过。未通过修改断言掩盖失败。

debug instrumentation 不能直接作为混淆 release 的页面测试：测试运行器依赖未混淆 Kotlin 类而启动失败。独立平台 UI 测试尝试也未完成，因此本记录不声称 release 页面回归已通过。

尚缺实际预览 deployment、正式签名 Release、覆盖安装、OEM Launcher、完整授权生命周期、48 小时后台及 7 天自用记录。四版本矩阵成功不代表这些项目完成。

GitHub 集成创建 PR 返回 403 `Resource not accessible by integration`，CLI 未登录，浏览器访问已被权限策略拒绝；未创建或合并新 PR。
