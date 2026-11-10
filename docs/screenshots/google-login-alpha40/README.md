# alpha40 Google 账号选择与自动填充

2026-11-09，alpha40 debug，API 36 ARM64 模拟器，1080×2400 原生截图，未裁切或重绘。

`google-account-picker-sample.png` 使用生产 `BrowserLoginLayout`、`BrowserLoginHeader`、`BrowserLoginFooter` 与 `QuotaBrowserView`，由 `GoogleAccountBrowserTest` 的原生 Activity 承载。网页是完全拦截网络的合成 HTML，明确标注测试样例，不是真实 Google 登录页；密码是固定测试值，截图展示的是新增系统账号选择入口。没有读取真实 Google 账号、密码或令牌。

验证范围：测试密码管理器通过 Android Autofill Framework 收到网页表单、显示已保存账号建议，选中后实际填入邮箱与密码；测试所选邮箱的转义、输入事件、只填邮箱、不自动提交、页面跳转使旧选择失效、伪装 Google 域名被拒绝。纯 JVM 测试覆盖来源限制与非法账号名。真实 Google 账号选择与各厂商密码管理器、Google 二次验证及 WebView 拒绝策略仍需对应设备验收，模拟测试不能替代这些流程。

另有 `google-account-picker-narrow.png`：320dp 宽、200% 系统字体，使用同一生产 UI 与合成表单。大字体或键盘弹出时收起说明与服务标题，保留完整域名和操作入口，网页仍可滚动输入。

构建、lint、六组设备流程与发布状态以关联 PR 和 Actions 的最终结果为准；debug 截图不表示 APK 已发布。
