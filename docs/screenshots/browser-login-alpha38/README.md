# alpha38 官方登录入口

2026-11-06，0.1.0-alpha38 debug / Android API 36 arm64 模拟器，1080×2400。

`SubscriptionsUiTest.officialLoginsReplaceSessionFormsAndKeepApiKeysSeparate` 通过 Android UiAutomation 保存完整原生屏幕，未裁切、拼接或重绘。三张 `login-*-sample.png` 为**合成测试额度**：测试拦截登录 Activity 启动并返回固定数据，不代表真实账号的 Android 登录验收。图片展示官网登录、官网刷新、缓存说明和独立断开；网页登录页本身启用防截屏。

使用 App 同一份 JS 读取脚本，在用户已经登录的桌面 Kimi、WorkBuddy、Grok 官网取得有效额度响应；不保存真实 Cookie 或 token。Android WebView 使用全网络拦截的合成页面验证三种脚本、WorkBuddy 数字 ID 分页、Grok 可选钱包失败、陌生来源隔离和销毁停止轮询。界面测试覆盖四个登录入口、API key 独立连接、旧数据保持与多语言。

本地 debug/release 构建、lint、297 项 JVM 测试和 84 项交付脚本测试通过；API 36 首轮 9 项设备用例通过。最终代码另通过 5 项定向复测，含三个真实独立进程的 Cookie 持久化与定向清除；最终 PR 和 master 的六设备矩阵、签名与发布状态以 Actions 为准。真实 Android 账号完整登录与所有第三方 SSO 尚未全量验收；不会绕过服务方验证。
