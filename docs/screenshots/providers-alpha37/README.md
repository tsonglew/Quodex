# alpha37 新增服务原生截图

2026-11-06，`0.1.0-alpha37` debug，Android API 37 arm64 模拟器（1080×2400）。`SubscriptionsUiTest` 通过 Android UiAutomation 直接保存完整屏幕，未裁切、拼接或重绘；画面中的数值均为合成测试数据，已按总览／账号／设置三入口导航重新拍摄。正常示例卡保留“示例数据 · 非真实账号”标记。

| 卡片 | 浅色 | 深色 |
| --- | --- | --- |
| Kimi 会员 | [原图](providers-kimi-light.png) | [原图](providers-kimi-dark.png) |
| WorkBuddy 国际站积分包 | [原图](providers-workbuddy-light.png) | [原图](providers-workbuddy-dark.png) |
| Grok／Cursor 共享订阅 | [原图](providers-grok-light.png) | [原图](providers-grok-dark.png) |
| Kimi 国内 API 余额 | [原图](providers-kimi_api-light.png) | [原图](providers-kimi_api-dark.png) |
| xAI API 预付余额 | [原图](providers-grok_api-light.png) | [原图](providers-grok_api-dark.png) |
| Cursor 团队费用 | [原图](providers-cursor_billing-light.png) | [原图](providers-cursor_billing-dark.png) |

[手动导入界面](providers-grok-manual-import.png)使用测试注入的 JSON，只含 37/100 的合成周用量；用于检查来源提示、导入时间、未知余额和不自动刷新的行为，不是用户真实账户。该图没有启用示例模式，以便验证实际导入操作；请勿将数值视为真实账单。

[320dp／200% 系统字体](providers-narrow-large-font.png)由 `UiExperienceTest` 使用原生 Compose 屏幕捕获，保留测试宿主和留白；检查新增六项服务的文本与控制台入口。截图不是签名发布包或真实设备兼容性证明。

浏览器登录字段核对、开发者 API 与 Grok 验证限制见 [订阅说明](../../SUBSCRIPTIONS.md)。旧 alpha32／alpha36 截图保留历史来源，不替代本组界面。
