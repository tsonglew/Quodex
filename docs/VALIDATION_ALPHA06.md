# alpha06 小组件手动刷新修复

2026-10-10，`0.1.0-alpha06`／versionCode 6。

原按钮只排入普通 WorkManager 任务；后台调度受限时点击不会立即请求。标准及 2×1 组件现在先在 Glance 回调中直接调用共享仓库的强制刷新，最多等待 8 秒；超时、临时失败或等待服务端限流时由唯一任务续办。Android 12+ 续办使用 expedited，配额不足回落普通任务；旧系统保留普通续办，避免引入未经实现的前台通知。

新手动续办替换旧的退避任务；仓库仍合并并发请求并保留服务端 Retry-After。取消时恢复此前的尝试记录，避免续办被误判为最近已成功获取。未连接时按钮打开连接页面。成功／失败后更新两种组件，失败保留成功快照与时间；标准组件增加具体失败提示，按钮提供“刷新额度”无障碍描述。

## 自动验证

- 45 项 JVM 测试全部通过。新增直接请求、超时取消与续办、失败分类、外部取消不创建任务，以及取消后续办不把旧快照冒充成功的回归。
- lint、debug App 与 instrumentation APK 构建通过。
- Android 35 原生回调验证通过：已完成的刷新回调不再创建普通后台等待任务。设备 12 项覆盖通过：首轮 9 项通过，组件截图曾因文本布局未完成失败；补充布局就绪等待、保留可见性／裁切／省略号断言后，3 项组件回归通过。最终针对缺失额度布局与刷新回调再跑 2 项，全通过（35.638 秒）。
- debug APK SHA-256：`dcbbf88211bf79d63ed04863a5755871bcbfd5dd8af3b7cf6e52a3779fe5f0c9`。

测试不使用真实账号凭据；真实额度请求由 MockWebServer 验证。小米 Android 17 的实际桌面点击、限流、网络与清理后台后的恢复仍待真机确认，不能由模拟器替代。

[Android 官方任务定义](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)说明 expedited 仍可能受到系统负载或配额影响，因此即时尝试与后台续办分开。系统强制停止后仍需用户重新打开应用。

## GitHub 验证

提交 `db3d853c471aafb54b8b8000a4d5983560bde707` 的 [CI 37981492470](https://github.com/tsonglew/Quodex/actions/runs/37981492470) 全部成功：交付检查、构建、单元测试、lint、API 29／31／35／36 设备测试与统一 CI gate。此结果不包含真机验收或正式签名发布。
