# alpha39 订阅自动更新

2026-11-09，0.1.0-alpha39 debug / Android API 36 arm64 模拟器，1080×2400。

`SubscriptionsUiTest.officialLoginsReplaceSessionFormsAndKeepApiKeysSeparate` 通过 Android UiAutomation 保存完整原生屏幕，未裁切、拼接或重绘。所有额度均为合成测试数据，登录 Activity 由测试拦截，不代表真实账号 Android 登录验收。

- `login-kimi-sample.png`、`login-workbuddy-sample.png`、`login-grok-sample.png`：登录后显示自动更新说明和普通刷新入口。
- `auto-refresh-grok-sample.png`：普通刷新完成后，保持在原生额度页，未再次打开登录 Activity。

App 打开或恢复前台时检查缓存；前台每分钟检查一次，成功快照五分钟内复用。网页读取使用原服务独立进程中的已保存登录态，不显示窗口；退出前台或进入 App 示例模式时取消自动读取，单次读取最长 45 秒。失败保留快照和时间；429 遵守 Retry-After，其他临时失败五分钟后再试；登录失效或验证要求暂停自动读取，提示打开官网登录。后台网页同步不在本轮范围。

本地验证：304 项 JVM 测试与 84 项交付脚本测试通过；debug/release 构建及 lint 通过。API 36 的 6 项订阅界面回归通过，覆盖普通刷新不打开登录、跨服务隔离、示例零请求和语言重建。WebView 定向回归覆盖连续读取、跨进程重复请求、旧请求取消隔离、登录失效、429 和 Cookie 分区。六设备完整矩阵、签名发布与更新索引以最终 Actions 结果为准。

首轮新增 IPC 测试的合成钱包缺失必填字段而被解析器拒绝；修正 fixture 后，6 项 WebView 用例全部通过；最终文案版本另通过官网登录及普通刷新界面定向复测。真实 Android Cursor／Grok 登录态在不同设备上的长期可用性仍需自用验收；不会绕过网站验证，不导出 Cookie 或令牌。
