# 订阅服务查询

源码版本 alpha32；可安装版本以 [GitHub Releases](https://github.com/tsonglew/Quodex/releases) 实际 APK 为准。2026-11-03 核对接口与实现；真实账户连通性、权限与长期兼容性尚待验收。

## 接入与展示

在悬浮导航进入“订阅”。每个服务单独连接、刷新和断开；不需要先登录 Codex，也不会覆盖 Codex 凭据。

| 服务 | 输入 | 查询内容 |
| --- | --- | --- |
| DeepSeek | 在开放平台创建的 API key | CNY／USD 总余额、赠送余额与充值余额；API 是否可用。与聊天产品独立。 |
| OpenCode Go | 所属 Go 工作区的 API key | 接口返回的滚动、周、月用量百分比与重置时间。不得据此推算 Token 或剩余请求数。 |
| OpenCode Console（原 Zen） | 具有账单读取权限的个人 API key | 工作区账本余额、可用额度与计费模式；实验性控制台兼容接入。 |
| Cursor | 个人控制台的 WorkosCursorSessionToken 值 | 服务端返回的 Cursor 模型、其他模型与总用量百分比、计费周期结束时间；启用按量付费且接口返回时显示按量费用。实验性兼容接入。 |

Cursor：在电脑浏览器登录 [官方控制台](https://cursor.com/dashboard)，打开开发者工具 → Application → Cookies，复制 `WorkosCursorSessionToken` 的**值**并在 App 连接框粘贴，不粘贴 Cookie 名称、完整请求头或其他 Cookie。不要将真实凭据发到聊天、Issue、日志或截图。会话过期后重新连接。个人 API key 与团队 Admin API 不可互换。

OpenCode Console：在 Account → Personal API keys 为目标工作区创建密钥，按账号角色选择账单读取权限。旧 Zen 推理密钥、缺少权限的密钥或服务端迁移状态可能无法访问新的 Console 账单接口；失败会明确显示权限／格式错误，并提供官方控制台入口，不假装查询成功。Go 和 Zen 分开连接，避免把 Go 用量当作钱包余额。

进入订阅页会查询已连接服务，五分钟内使用缓存；也可手动刷新，查询中可取消。临时网络错误、408、429、500／502／503／504 最多共尝试三次，遵守 Retry-After；401／403／格式错误不自动重试。每个服务独立记录界面错误与等待截止时间，失败保留上次成功数据及原始更新时间，断开连接会阻止晚到的请求重新写入缓存。示例模式不向服务方发送示例密钥或请求。

凭据与快照共同使用 Android Keystore 和 AES-GCM 加密；禁止网络重定向，不向其他服务、GitHub 或诊断记录发送凭据。每个服务仅保存一个连接，重连成功后才替换旧连接。设置中的 Codex 退出只影响 Codex，新服务应在各自卡片断开。卸载或清除应用数据会清除全部本机连接。远端撤销须使用提供方控制台。

断开操作独立于网络请求执行；快速重新连接、取消查询或关闭当前页面，不会取消已经发起的凭据删除。如果本机加密存储无法读取，订阅页会阻止新增连接并提供“重试读取数据”和“重置订阅数据”入口。清除前需要确认，并会再次尝试读取；若恢复可读则保留连接，仍无法读取时才清除这四项服务的本机凭据与缓存，随后可重新连接。此恢复操作不清除 Codex 连接、语言或其他设置，也不撤销远端授权。

## 数据与兼容边界

- DeepSeek 金额保留十进制精度，不合并不同币种；不足或负余额不会显示为充足。
- OpenCode Console 的金额单位为整数 microcent，1 USD = 100,000,000 microcents；余额可以为负，信用计费的可用额与账本余额不一定相等。
- Cursor 使用服务端给出的百分比，不用旧版美元用量／上限反推新模型池百分比；套餐内用量不是待支付账单。未返回的项显示“未提供”。新版套餐若返回不同结构，会显示未支持，不臆造数据。
- 不硬编码套餐价格或承诺可调用次数。小组件、桌面额度图标、Token 图表与后台同步仍仅面向 Codex。此次不覆盖团队管理 API、多账户列表或自动续期第三方会话。
- OpenCode Console 与 Cursor 的接口不是稳定的公开个人用量 API；页面、权限或账号迁移变化可能造成不兼容。可在 App 打开官方控制台核对。

## 接口依据（2026-11-03）

1. [DeepSeek 余额接口](https://api-docs.deepseek.com/api/get-user-balance/)：`GET https://api.deepseek.com/user/balance`，Bearer key，`is_available` 与 `balance_infos`。
2. [OpenCode Go 官方历史实现](https://github.com/anomalyco/opencode/blob/d4704347465c1ee63d0c213ed00e648e7f0231c5/packages/console/app/src/routes/zen/go/v1/usage.ts)：`GET https://opencode.ai/zen/go/v1/usage`，Bearer key；`usage.rolling/weekly/monthly` 中的 `percent`、`resetsAt`。后续迁移为推理代理，故需继续核对真实账户。
3. [OpenCode 当前 Console](https://opencode.ai/console/login) 发布的 `assets/index-CyBnKQjT.js`：API client 基于 `/console/`，Billing.getStatus 使用 `GET /api/billing/status`；WorkspaceMiddleware 支持 Bearer，权限列表含 `billing:read`。BillingStatus 包含 `billingMode`、`balanceMicroCents`、`availableMicroCents`；`oK()` 为每美元 100000000 microcents。无凭据请求 `/console/api/billing/status` 返回 401，不将此视为真实连接通过。
4. [OpenCode API key 文档](https://opencode.ai/v2/docs/console/api/)及 [Go 说明](https://opencode.ai/v2/docs/console/go/)用于接入指引，不把公开推理文档当作账单接口稳定性承诺。
5. [Cursor 官方论坛中的控制台响应样本与团队回复](https://forum.cursor.com/t/cursor-usages-is-not-showing-correct-information/170210)：`/api/usage-summary` 的个人用量字段；[Cookie 接入实测说明](https://forum.cursor.com/t/cursor-usage-an-extension-to-track-your-auto-and-api-usage-new/150761)。这是原始用户实测和兼容证据，非 Cursor 公开 API 合约；[个人用量 API 的官方回复](https://forum.cursor.com/t/usage-api-cli-command/160967)明确当时尚无公开接口。

## 验证记录

2026-11-03 本地验证：

- 228 项 JVM 测试通过，其中新增 24 项解析、HTTP、仓库与操作调度测试，覆盖币种／精度、microcent 换算、缺失值、超额、重定向隔离、凭据头、取消、限流、缓存、失败重连、写盘失败、跨服务隔离和晚到请求；另覆盖不可读缓存恢复、恢复前重试保护可读凭据，以及断开后快速重连／取消不影响凭据删除。
- API 37、arm64 模拟器的 9 项订阅与界面设备测试通过，含新增 4 项订阅测试：取消首次连接不落盘，连接／鉴权失败／断开隔离，加密文件不含明文凭据，示例零请求，英文与日文切换重建。现有界面测试增加 320dp／200% 系统字体的订阅页和四项导航检查。
- debug／release 构建与 lint、55 项发布脚本测试通过。生产代码评审修复后，本地 API 37 全套 83 项设备测试入口执行完成，无失败（包含按系统能力跳过的用例）。
- 远端首轮验证暴露 API 37 冷启动桌面 ANR 与语言测试收尾卡住：测试启动改用唤醒／解锁、在安装 App 前重启一次已知模拟器桌面；语言测试清理使用 Application，并通过系统语言管理恢复。完整远端矩阵仍以 PR／Actions 的最终状态为准。
- 同一 API 37 模拟器使用生产 debug 入口注入损坏文件：重试后仍显示错误，取消清除保留文件，确认清除后错误消失且可重新连接，设置文件散列不变；[恢复确认框](screenshots/subscriptions-alpha32/subscriptions-recovery.png)为 alpha32 debug 故障注入截图，不含真实账号。
- [中文 DeepSeek](screenshots/subscriptions-alpha32/subscriptions-deepseek.png)、[OpenCode Go](screenshots/subscriptions-alpha32/subscriptions-opencode-go.png)、[OpenCode Console](screenshots/subscriptions-alpha32/subscriptions-opencode-zen.png)、[Cursor](screenshots/subscriptions-alpha32/subscriptions-cursor.png)、[English](screenshots/subscriptions-alpha32/subscriptions-en.png)、[日本語](screenshots/subscriptions-alpha32/subscriptions-ja.png)、[320dp／200% 字体](screenshots/subscriptions-alpha32/subscriptions-narrow-large-font.png)均为 alpha32 debug 原生截图、示例数据。

样例与 MockWebServer 测试不能代替真实账号验收。尚未使用真实 DeepSeek／Go／Console／Cursor 凭据验证，不能据此承诺每种套餐与迁移中的工作区都可连接。
