# 订阅与 API 账单查询

源码版本 alpha37，2026-11-06 核对；可安装版本以 [GitHub Releases](https://github.com/tsonglew/Quodex/releases) 实际 APK 为准。界面提供“全部”“订阅额度”“API 账单”筛选。各项连接独立，不要求先登录 Codex。

## 支持范围

| 服务 | 凭据／方式 | 显示内容与限制 |
| --- | --- | --- |
| Kimi | kimi.com 的 `access_token` | 会员套餐已用比例、到期时间与有效加油包余额；实验性网页接口。 |
| Kimi Code | Kimi Code API key | 官方用量接口返回的总用量及具体窗口，不把未知周期称为周额度。当前使用国内 coding 端点。 |
| Kimi API · CN / Global | 对应区域的平台 API key | 国内 CNY、国际 USD 的可用／赠送／充值余额；分开连接，密钥不跨区域回退。 |
| WorkBuddy | 国际站的 `session`、`session_2` 与原浏览器 User-Agent | 按有效资源包显示当前周期剩余／总积分，保留小数。到期墙上时间标注为“服务器时间”，不猜测时区。国内站暂不支持。 |
| Grok / Cursor | 官网 JSON 导入；会话连接为实验性入口 | 已迁移账号的共享周额度、额外用量费用；自动查询返回时另显示账户余额，不重复计算两份订阅。原生请求实测遇到网页验证，请使用手动导入。 |
| Grok API | xAI 团队 Management API key | 独立的开发者 API 预付钱包；不接受推理 key 或组织级管理 key，不充值。 |
| Cursor Admin API | 团队 Admin API key | 遍历所有成员分页，汇总本周期按量费用与总费用，包含计入该团队的 Cloud Agents。费用不是剩余额度；不保存成员身份信息。 |
| Cursor（旧接口） | WorkosCursorSessionToken | 旧个人控制台模型用量与计费周期，保留已有连接。已迁移到 Grok 控制台的账号使用 Grok / Cursor 卡片，旧接口可能不可用。 |
| DeepSeek | API key | 按 CNY／USD 分别显示总余额、赠送与充值余额、API 可用性。 |
| OpenCode Go | Go 工作区 API key | 滚动／周／月用量和重置时间，存在接口迁移风险。 |
| OpenCode Console（原 Zen） | 有账单读取权限的个人 key | 工作区账本余额、可用额度与计费模式，实验性控制台接口。旧推理 key 不一定适用。 |

WorkBuddy 签发的 API key 用于 CodeBuddy CLI，没有据此推断独立的模型 API 钱包。自定义模型使用自带密钥时，费用与余额归 Kimi、DeepSeek 等模型服务商，在“API 账单”筛选中连接相应提供方。Cursor 的个人 Cloud Agents key 与团队 Admin key 不可互换。

## 连接和手动导入

Kimi：在电脑登录 [会员页面](https://www.kimi.com/settings/subscription)，开发者工具 → Application → Local Storage 找到 `access_token`，只复制值。WorkBuddy：在 [国际站用量页面](https://www.workbuddy.ai/profile/plans-usage) 的 Application → Cookies 分别复制 `session`、`session_2` 的值，再从该浏览器 Network 请求头复制 User-Agent，填入三个字段；服务会将会话绑定到浏览器值。App 不收集整份 Cookie 或浏览器配置。

Grok / Cursor：在 [官方用量页](https://grok.com/dashboard/usage) 打开 Network，复制 `usage-status?scope=user` 的 JSON **响应**，使用卡片“导入官网数据”粘贴。不要粘贴请求头、Cookie 或聊天内容。仅保留额度／费用字段；未包含的账户余额显示未知。卡片标注导入时间与手动来源，导入时间不等于数据生成时间，之后必须重新导入更新。导入快照在重启后仍不会自动联网，断开会删除它。格式错误保留原快照。

Grok 的“连接”可尝试 `WorkosCursorSessionToken`，但本轮原生 HTTP 请求返回网页验证错误；不转存验证 Cookie 或自动绕过验证。旧 Cursor 连接同样只接受单个 `WorkosCursorSessionToken` 值，不接受完整 Cookie 请求头。真实凭据不要发到聊天、Issue、日志或截图。

xAI：在官方控制台创建具有预付账单读取权限的团队 Management key；App 先核对 key 的团队作用域，再读取钱包。Cursor Admin API 需团队管理员可用的 Admin key；不以个人控制台会话替代。公开 API 的模拟测试不代表真实密钥和所有套餐权限已验收。

## 刷新、缓存和隐私

打开订阅页时查询已连接服务，五分钟内使用缓存；手动导入除外。可手动刷新或取消。临时网络错误、408、429、500／502／503／504 最多尝试三次并遵守 Retry-After；401／403／格式错误不自动重试。失败保留上次成功数据和原始时间。重连成功后才替换旧连接，断开与晚到请求隔离。每项仅保存一个连接，没有跨账号合并或自动续期网页会话。

凭据及对应快照使用 Android Keystore / AES-GCM 加密，禁止网络重定向；不保存原始响应、成员姓名、邮箱或账号列表，不写入诊断。WorkBuddy 原浏览器 User-Agent 与两段会话一起加密，仅发给 workbuddy.ai。导入只保留解析后的数值，不保存原始 JSON。各服务仅前台查询，不新增小组件、Token 图表或后台同步；示例模式不联网。

各卡片独立断开；Codex 退出不清理其他连接。不可读加密存储提供重试和确认后重置，清除前再次尝试读取，避免删除可恢复数据；重置覆盖全部订阅连接与导入快照，不影响 Codex 和偏好。卸载或清除应用数据删除本机连接，远端撤销须使用官方控制台。

金额保留十进制精度与币种；未知值不补零。Kimi API 充值余额允许为负，以可用余额判断是否可用。xAI 钱包账本以负数记充值，展示时取相反数，负可用额不取绝对值。Grok 的按量消费上限不是余额。WorkBuddy 是积分，不冒充货币。Cursor 分页数量变化或重复时拒绝汇总，不显示不完整的团队费用。

## 本轮接口依据与验证范围

2026-11-06（北京时间）：在用户已登录的官方页面核对 Kimi、WorkBuddy、Grok／Cursor 的当前字段。Kimi 和 WorkBuddy 同时完成浏览器外的只读 HTTP 查询；没有将真实凭据写入仓库或 Android 测试包。Grok 浏览器内成功，原生请求受到网页验证限制。以下为兼容证据，不承诺服务方私有接口稳定。

- Kimi 会员：`POST https://www.kimi.com/apiv2/kimi.gateway.membership.v2.MembershipService/GetSubscriptionStats`，Bearer access_token、空 JSON、Connect 协议版本 1；解析 `subscriptionBalance` 与有效 `boosterWallets`。[会员规则](https://www.kimi.com/help/membership/membership-update-rules)。
- Kimi Code：`GET https://api.kimi.com/coding/v1/usages`，Bearer；按 `usage` 和 `limits[].window/detail` 解析。[官方 CLI 实现](https://github.com/MoonshotAI/kimi-cli/blob/9ab1286b8fe4e6bcd116949a27ce5e0ac3389c82/src/kimi_cli/ui/shell/usage.py)。
- Kimi API：`GET https://api.moonshot.cn/v1/users/me/balance` 或 `.ai` 同路径，Bearer。[国内官方文档](https://platform.kimi.com/docs/api/balance.md) · [国际余额说明](https://www.kimi.ai/help/kimi-api/api-balance-and-usage)。
- WorkBuddy 国际站：`POST /billing/meter/get-user-resource`，两段 session Cookie、原 User-Agent；查询 `p_tcaca` 的有效积分包并遍历分页，解析 `CycleCapacityRemainPrecise`／`CycleCapacitySizePrecise`。[用量规则](https://www.workbuddy.ai/docs/zh/workbuddy/usage) · [自定义模型归属](https://www.workbuddy.ai/docs/zh/workbuddy/From-Beginner-to-Expert-Guide/Function-Description/Model-Configuration)。
- Grok／Cursor：`GET https://grok.com/dashboard/api/dashboard/v2/usage-status?scope=user` 读取共享池与费用；另以只读 `POST get-complat-credit-balance` 获取 `usdRemainingCents`，不计入待退款或待入账金额。[Cursor 用量说明](https://cursor.com/help/models-and-usage/usage-limits)。自动兼容入口与手动导入使用相同字段解析。
- xAI API：`GET https://management-api.x.ai/auth/management-keys/validation` 核对团队；`GET /v1/billing/teams/{team}/prepaid/balance` 读取 `total.val` 的有符号 USD 分。[认证文档](https://docs.x.ai/developers/rest-api-reference/management/auth.md) · [账单文档](https://docs.x.ai/developers/rest-api-reference/management/billing.md)。
- Cursor Admin：`POST https://api.cursor.com/teams/spend`，Basic key + 空密码；`page`／`pageSize` 分页，保留分值小数精度。[官方 Admin API](https://cursor.com/docs/account/teams/admin-api.md)。
- 原有服务依据：[DeepSeek](https://api-docs.deepseek.com/api/get-user-balance/) · [OpenCode Go 历史实现](https://github.com/anomalyco/opencode/blob/d4704347465c1ee63d0c213ed00e648e7f0231c5/packages/console/app/src/routes/zen/go/v1/usage.ts) · [Console API key](https://opencode.ai/v2/docs/console/api/) · [旧 Cursor 控制台响应记录](https://forum.cursor.com/t/cursor-usages-is-not-showing-correct-information/170210)。

本地 290 项 JVM 测试、67 项交付脚本测试、debug／release 构建与 lint 通过；新增 21 项解析、HTTP、分页、导入缓存与并发隔离测试。API 37 上 6 项订阅界面测试和 1 项 320dp／200% 字体检查通过（断开测试修正悬浮导航遮挡后单独重跑通过）。开发者 API 尚未使用真实 key 验收；浏览器外 HTTP 成功也不代表所有 Android 设备与账号均可用。原生界面来源见 [alpha37 截图记录](screenshots/providers-alpha37/README.md)，远端矩阵以 PR 的最终状态为准。

## 历史 alpha32 验证记录

2026-11-03 本地验证：

- 228 项 JVM 测试通过，其中新增 24 项解析、HTTP、仓库与操作调度测试，覆盖币种／精度、microcent 换算、缺失值、超额、重定向隔离、凭据头、取消、限流、缓存、失败重连、写盘失败、跨服务隔离和晚到请求；另覆盖不可读缓存恢复、恢复前重试保护可读凭据，以及断开后快速重连／取消不影响凭据删除。
- API 37、arm64 模拟器的 9 项订阅与界面设备测试通过，含新增 4 项订阅测试：取消首次连接不落盘，连接／鉴权失败／断开隔离，加密文件不含明文凭据，示例零请求，英文与日文切换重建。现有界面测试增加 320dp／200% 系统字体的订阅页和四项导航检查。
- debug／release 构建与 lint、55 项发布脚本测试通过。生产代码评审修复后，本地 API 37 全套 83 项设备测试入口执行完成，无失败（包含按系统能力跳过的用例）。
- 远端首轮验证暴露 API 37 冷启动桌面 ANR 与语言测试收尾卡住：测试启动改用唤醒／解锁、在安装 App 前重启一次已知模拟器桌面；语言测试清理使用 Application，并通过系统语言管理恢复。完整远端矩阵仍以 PR／Actions 的最终状态为准。
- 同一 API 37 模拟器使用生产 debug 入口注入损坏文件：重试后仍显示错误，取消清除保留文件，确认清除后错误消失且可重新连接，设置文件散列不变；[恢复确认框](screenshots/subscriptions-alpha32/subscriptions-recovery.png)为 alpha32 debug 故障注入截图，不含真实账号。
- [中文 DeepSeek](screenshots/subscriptions-alpha32/subscriptions-deepseek.png)、[OpenCode Go](screenshots/subscriptions-alpha32/subscriptions-opencode-go.png)、[OpenCode Console](screenshots/subscriptions-alpha32/subscriptions-opencode-zen.png)、[Cursor](screenshots/subscriptions-alpha32/subscriptions-cursor.png)、[English](screenshots/subscriptions-alpha32/subscriptions-en.png)、[日本語](screenshots/subscriptions-alpha32/subscriptions-ja.png)、[320dp／200% 字体](screenshots/subscriptions-alpha32/subscriptions-narrow-large-font.png)均为 alpha32 debug 原生截图、示例数据。

样例与 MockWebServer 测试不能代替真实账号验收。尚未使用真实 DeepSeek／Go／Console／Cursor 凭据验证，不能据此承诺每种套餐与迁移中的工作区都可连接。
