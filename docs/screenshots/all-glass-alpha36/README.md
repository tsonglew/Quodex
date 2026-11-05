# alpha36 全界面玻璃与 API 赠送额度

拍摄日期：2026-11-06（Asia/Shanghai）。来源：`0.1.0-alpha36` debug APK、Android API 37 arm64 独立模拟器、1080 × 2400 屏幕。`GlassMaterialDeviceTest.glassPagesRemainReachableInBothThemes` 和 `ApiCreditsUiTest` 通过 Android 原生屏幕捕获保存；没有生成、裁切或重绘。

所有页面均使用本机示例模式。API 卡的每月 US$100／可领取 US$75 为合成测试值，不代表拍摄者的套餐、余额或真实领取资格。页面截图保留示例数据标记。实际登录账单的内容没有放入截图或仓库。

- `all-glass-{quota,subscriptions,widgets,settings}-{light,dark}.png`：四页浅深色玻璃表面。
- `api-credits-{light,dark}.png`：展开福利卡，区分周期福利、可领取金额和未提供的剩余余额。

本机检查：267 项 JVM 测试通过，Debug／Release 构建和 lint 完成（0 错误）；两主题页面切换，以及热力图添加、刷新和打开 App 流程通过。此前的模拟器未验证网络会阻止 WorkManager 网络任务；通过本机代理恢复正常联网后，未修改断言的热力图测试通过。只验证模拟器，不扩展为全部真机或真实账号兼容性声明。

[材质实现](../../LIQUID_GLASS.md) · [API 查询边界](../../API_CREDITS.md)。签名包和发布状态以 GitHub Releases 为准。
