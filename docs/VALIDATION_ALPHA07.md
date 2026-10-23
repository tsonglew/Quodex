# alpha07 隐私入口与 release 验证

2026-10-10，`0.1.0-alpha07`／versionCode 7。

## 交付

- 设置页与连接对话框新增离线隐私入口，读取同一份打包文本，说明网络数据、加密凭据与未加密缓存、桌面展示、保留设置、本地清除与远程撤销的区别。政策及源码核对见 [PRIVACY.md](PRIVACY.md)。
- 登录对话框支持滚动，隐私弹窗可选择文字并滚动查看。
- 发布 CI 下载同次 unsigned release，在四版本模拟器以一次性测试密钥验证实际混淆 APK。手动 CI 也能选择验证 release，不触及正式凭据或发布资产。

## 已执行证据

- 本地 45 项 JVM 测试、debug lint、App／测试 APK 构建通过。
- Android 35 的 3 项 AppFlow 测试覆盖通过：首轮 2 项既有流程通过，新增隐私流程的截图因匹配到两个根窗口失败；改为捕获实际对话框后，该测试再次通过（6.761 秒）。验证设置和连接前查看、关闭与返回，已目视检查深色弹窗原生截图。
- `lintRelease assembleRelease` 成功（1 分 57 秒）；R8 开启，实际 release manifest versionCode=7、allowBackup=false、usesCleartextTraffic=false，未开启 debuggable，未包含 debug WidgetTestHostActivity。
- 实际混淆 release 由 `scripts/release_smoke.sh` 使用一次性密钥签名并验证，拒绝真机执行。标准 AOSP 35 上 `RELEASE_SMOKE_OK`：启动 → 示例 → 组件页 → 设置 → 深色 → 隐私说明 → 退出示例 → 未连接页面。
- actionlint、ShellCheck、Python 语法检查及 11 项交付脚本测试通过。
- 本地 Gradle 文件监听曾漏掉新文件；关闭监听并重新执行构建，确认新增源码与资源被编译。此后本机验证使用 `--no-watch-fs`。

| APK | SHA-256 |
| --- | --- |
| debug | `ffa1b571f09c4fd63038ba52ca77f3240e3ab0e8a7610e98360b070e1c4d1f2b` |
| unsigned release | `885bbb5e08debd3f8b9478c06356971f33fd97de29de044a7752d4794552c6ad` |

本轮 release 检查在 Android 35 本地实际执行；新增四版本 release CI 配置尚待 GitHub 运行，不能与已通过的 [alpha06 四版本 debug 设备矩阵](https://github.com/tsonglew/Quodex/actions/runs/37981492470) 混同。没有正式签名发布、覆盖升级、真实授权续期或 OEM／长期观察证据。
