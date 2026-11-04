# Codex 重置卡界面（alpha34 开发版）

来源：`codex/reset-credit-info` 的 0.1.0-alpha34 debug 构建，2026-11-04 通过 `ResetCreditsUiTest` 在专用 Android 10 / API 29 arm64 模拟器渲染原生 `ResetCreditsCard` 组件后直接捕获，非设计稿或网页仿制。固定示例时间 2026-11-04，模拟器时区 Asia/Shanghai；所有数量、卡片和日期为合成示例数据，不代表真实账号权益，没有向服务端发送示例凭据。

- [中文展开明细](reset-details-zh-Hans-light.png)：两张示例卡，含状态、获得与到期日期。
- [中文深色数量](reset-cards-zh-Hans-dark.png)：默认收起状态。
- [英文展开明细](reset-details-en-light.png)、[日文展开明细](reset-details-ja-light.png)。

图片为 945×2211 的原生测试视口，展示额度页中的重置卡区域，未包含整页其他组件。测试同时覆盖三种语言的深浅主题、320dp 两倍字号、未知与零张、独立限流提示、部分／过期缓存、Dashboard 展开查询及保留额度；以构建和测试报告为准，不扩展为真实账号或真机覆盖声明。

是否已发布以 [GitHub Releases](https://github.com/tsonglew/Quodex/releases) 的实际 APK 为准。
