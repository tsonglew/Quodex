# alpha16 正式签名覆盖升级验证

日期：2026-10-10。版本 0.1.0-alpha16／16，master `d356cd8c06726fb15e6937f576b52c3a384d68c5`。**自动出包、独立签名核验和正式 alpha15→alpha16 覆盖升级均已通过，F02 完成。**

## 自动产物

[master 运行 38037985183](https://github.com/tsonglew/Quodex/actions/runs/38037985183) 由合并 PR #16 自动触发；交付脚本、71 项 JVM、debug／release lint、R8、API 29／31／35／36／37.0／37.2 全部设备与 minified 页面检查、统一 gate 及固定签名全部成功。

[下载 android-signed-apk](https://github.com/tsonglew/Quodex/actions/runs/38037985183/artifacts/11664782203)，保留 14 天。此次没有创建公开 GitHub Release 或版本 tag。

| 校验 | alpha15 基线 | alpha16 目标 |
| --- | --- | --- |
| 源提交 | a983dfd9198356340795bbdc2e30b6aa9ecca3ed | d356cd8c06726fb15e6937f576b52c3a384d68c5 |
| Actions 运行 | 38031990526 | 38037985183 |
| versionCode | 15 | 16 |
| APK SHA-256 | 85bbcc69e94f39a7f5334f4ecdb0e74c142e16d312c7befa316b08e99c9aeb26 | 77d04ec3ef52ef5dce25c7a8849248a8c30d4951bd67649f859a0fb50631a28b |

下载后独立执行 apksigner、zipalign 和 aapt2，核对 provenance、SHA256SUMS 及 APK 实际字节。两个包均为 `com.tsonglew.quotapal`，minSdk 29／targetSdk 36、非 debuggable、16 KB 对齐；共同签名证书 SHA-256 为 `5e7980c122100ccf9799a5174cb0e5a1748b8c442c09c015e145003145d2b5d4`，与 android-release 的公开指纹变量一致。[目标 APK 核验结果](diagnostics/alpha16-upgrade/apk-verification.json)。未读取本机签名 keystore 或密码。

## 实际覆盖升级

在任务专用 Android 12／API 31 ARM64 Pixel Launcher 模拟器执行。安装来自上表的正式 minified alpha15，通过生产认证和额度入口连接合成账号，写入真实 Keystore／Room／DataStore；设置深色、显示已用、60 分钟间隔，并添加两个实际桌面组件。随后以 `adb install --no-incremental -r` 覆盖安装正式 alpha16，没有卸载或清除数据。

| 验证 | 实际结果 |
| --- | --- |
| 应用身份 | 版本 15→16，UID 10151 保持 |
| 本机缓存 | 已用 38%、完整快照及 fetchedAt=1791620289 原样恢复；授权和额度请求计数均未增加 |
| 加密凭据 | 覆盖后密文 SHA-256 未变；不保存或输出明文凭据 |
| 应用设置 | DataStore 文件哈希未变；界面确认深色、显示已用、60 分钟 |
| 桌面配置 | 原 ID 8／9 保持；紧凑深色显示已用 38%，标准浅色显示剩余 62% |
| 后台任务 | 原唯一周期任务 ID 保持，interval=3600000ms，没有新增重复任务 |
| 401 续期 | 实际点击刷新：一次拒绝、一次续期、一次额度重试成功；没有新授权／交换 |
| 更新与落盘 | 已用变为 17%，标准组件剩余 83%，两个组件更新时间更新；新凭据密文已写入 |
| 进程重启 | 新缓存恢复无额外请求；随后手动刷新只增加一次成功额度请求，没有再次续期或 401，证明持久化的新凭据可复用 |

[升级前状态](diagnostics/alpha16-upgrade/before.json)、[缓存恢复](diagnostics/alpha16-upgrade/after-cached.json)、[续期落盘](diagnostics/alpha16-upgrade/after-renewal.json)、[重启后刷新](diagnostics/alpha16-upgrade/after-restart-refresh.json) 和 [比较断言](diagnostics/alpha16-upgrade/assertions.json) 保存合成数据、哈希和安全计数。续期后偏好文件哈希因刷新时间字段变化而改变；实际显示偏好另由 UI 检查确认，不能要求整个文件在刷新后仍不变。未知系统流量的 blocked 计数不用于额度请求断言。

原生截图：[升级前组件](screenshots/alpha15-upgrade-baseline-widgets.png)、[升级后原缓存组件](screenshots/alpha16-cached-widgets.png)、[保留的设置](screenshots/alpha16-cached-settings.png)、[续期后主界面](screenshots/alpha16-renewal-app.png)、[续期后两组件](screenshots/alpha16-renewal-widgets.png)。已实际检查设置及组件像素。首次 UI 断言误将主界面的数字和独立 `%` 文本视为一个节点，修正宿主脚本后完整通过；该失配没有新增网络请求或修改应用数据。

此结论覆盖当前 alpha15→alpha16 数据结构的正式升级和合成服务续期，不代表未来数据库 schema 迁移、真实账号权限／续期、OEM、48 小时或七天观察完成。

## 合成 HTTPS 服务与清理

`scripts/upgrade_lab_server.py` 只监听 127.0.0.1，只响应 auth.openai.com 和 chatgpt.com 的固定夹具路径，不提供外部转发，也不记录请求正文、凭据或任意 URL。专用模拟器信任一次性测试 CA，真实服务域名的直接解析阻断到设备环回地址，再使用本机代理。生产 APK 及网络信任策略没有修改，全部额度和账号数据为合成数据，没有使用示例模式。

脚本 25 项回归通过，包含未信任 CA 拒绝、未知 CONNECT 拒绝、首次交换、401 与 refresh token 轮换；TLS 清理修正后以 ResourceWarning=error 再次通过。第一次因沙箱禁止本机监听而失败，获准在本机网络环境执行后通过；不将权限错误计作逻辑成功。

实验结束后卸载合成应用、恢复模拟器 hosts、删除测试 CA、清除代理并停止该专用 AVD；首次恢复 hosts 因系统只读被拒绝，重新 remount 后再核对清理结果。合成服务已停止，一次性 CA／服务器私钥已删除；测试私钥不入库。

## 修复与回归过程

初始候选 31b7ebc 的 [CI 38035733101](https://github.com/tsonglew/Quodex/actions/runs/38035733101) 完整六组及 gate 成功。并行运行的 master f5edf96 在 API 37.0 被 Pixel Launcher 无响应弹窗遮挡，真实失败截图及系统日志见 [测试指南](TESTING.md#模拟器桌面弹窗隔离)。随后增加该已确认桌面变体的严格处理，并以最终提交重新运行 CI。

本机更新后的 debug／androidTest 编译成功，专用 API 31 模拟器上 WidgetRefreshActionDeviceTest 两项实际通过（4.838 秒），保留三组件进度、唯一请求及最终额度断言；本轮没有出现桌面弹窗，不能据此声称已实际执行弹窗关闭分支。本机独立 release 构建达到 600 秒预算后退出 124，不算通过；最终 minified 构建已由上方 master 云端成功结果补齐。

追加受控桌面故障对照：只在已核对名称的专用 API 31 AVD 暂停系统 Pixel Launcher，再注入输入造成实际 ANR，随后 finally 恢复该进程。输入命令达到 20 秒限时，并非普通成功返回；后续 UI XML 和系统日志实际确认出现完整匹配的弹窗。运行原三组件真实点击测试，处理分支日志明确记录关闭 com.google.android.apps.nexuslauncher，保留[关闭前截图](screenshots/emulator-pixel-launcher-recovery-api31.png)，原断言通过（5.908 秒）。这证明已执行恢复分支，不证明桌面自身 ANR 被修复。

433a66c 的 [CI 38036477159](https://github.com/tsonglew/Quodex/actions/runs/38036477159) 最终五组成功、API 35 失败、gate 正确失败。失败来自诊断报告预览的 Dialog 创建：[完整栈](diagnostics/alpha16-compose-test-thread.txt) 包含 DefaultDispatcher、TestMonotonicFrameClock、ApplyingContinuationInterceptor，尚未在生产运行器复现。[AndroidX 测试源码](https://android.googlesource.com/platform/frameworks/support/+/202b4bda8adfeb303ac30e359e8436a3752fe2b0/compose/ui/ui-test/src/commonMain/kotlin/androidx/compose/ui/test/ApplyingContinuationInterceptor.kt) 说明此拦截器在恢复 continuation 后发送快照通知；据堆栈推断，测试的不受线程约束的恢复路径触发了后台重组。候选将预览／清除协程显式约束到 Android 主调度器，文件读取仍在 IO，增加五轮实际预览／清除及额度请求数不变断言；最终六组 CI 已重新验证。

主线程约束修订后，debug／androidTest 重新构建成功，专用 API 31 AppFlowTest 四项全部通过（18.166 秒），包含五轮预览／清除、共十次报告弹窗，以及额度请求数不变断言。实际签名升级前已卸载该 debug 测试数据，重新安装正式 alpha15 并经其自身连接入口建立基线；没有将 debug 数据或测试签名当作升级基线。

最终候选 a361ded7 的 [CI 38037325156](https://github.com/tsonglew/Quodex/actions/runs/38037325156) 六组设备、构建、交付检查及统一 gate 全部通过；PR #16 已合并，master 自动出包运行 38037985183 的实际 release 构建和固定签名验收也已通过。
