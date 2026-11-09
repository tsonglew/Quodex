# 订阅与 API 账单查询

源码版本 alpha38，2026-11-06 核对；可安装版本以 [GitHub Releases](https://github.com/tsonglew/Quodex/releases) 实际 APK 为准。界面提供“全部”“订阅额度”“API 账单”筛选。各项连接独立，不要求先登录 Codex。

## 支持范围

| 服务 | 凭据／方式 | 显示内容与限制 |
| --- | --- | --- |
| Kimi | App 内官网登录 | 会员套餐已用比例、到期时间与有效加油包余额；实验性网页接口。 |
| Kimi Code | Kimi Code API key | 官方用量接口返回的总用量及具体窗口，不把未知周期称为周额度。当前使用国内 coding 端点。 |
| Kimi API · CN / Global | 对应区域的平台 API key | 国内 CNY、国际 USD 的可用／赠送／充值余额；分开连接，密钥不跨区域回退。 |
| WorkBuddy | App 内国际站官网登录 | 按有效资源包显示当前周期剩余／总积分，保留小数。到期墙上时间标注为“服务器时间”，不猜测时区。国内站暂不支持。 |
| Grok / Cursor | App 内官网登录 | 已迁移账号的共享周额度、额外用量费用；自动查询返回时另显示账户余额，不重复计算两份订阅。网页登录后自动读取额度；需要验证时在官方页面完成。 |
| Grok API | xAI 团队 Management API key | 独立的开发者 API 预付钱包；不接受推理 key 或组织级管理 key，不充值。 |
| Cursor Admin API | 团队 Admin API key | 遍历所有成员分页，汇总本周期按量费用与总费用，包含计入该团队的 Cloud Agents。费用不是剩余额度；不保存成员身份信息。 |
| Cursor（旧接口） | 保留旧连接；新登录转到共享池 | 旧个人控制台模型用量与计费周期，保留已有连接。已迁移到 Grok 控制台的账号使用 Grok / Cursor 卡片，旧接口可能不可用。 |
| DeepSeek | API key | 按 CNY／USD 分别显示总余额、赠送与充值余额、API 可用性。 |
| OpenCode Go | Go 工作区 API key | 滚动／周／月用量和重置时间，存在接口迁移风险。 |
| OpenCode Console（原 Zen） | 有账单读取权限的个人 key | 工作区账本余额、可用额度与计费模式，实验性控制台接口。旧推理 key 不一定适用。 |

WorkBuddy 签发的 API key 用于 CodeBuddy CLI，没有据此推断独立的模型 API 钱包。自定义模型使用自带密钥时，费用与余额归 Kimi、DeepSeek 等模型服务商，在“API 账单”筛选中连接相应提供方。Cursor 的个人 Cloud Agents key 与团队 Admin key 不可互换。

## 官方登录与 API key

在“账号”选择 Kimi、WorkBuddy 或 Grok／Cursor，点击“官网登录”，在 App 内官方页面完成登录或验证。额度读取成功后自动返回，不需要复制 Cookie、token、User-Agent 或 JSON。登录界面显示当前网站域名，支持关闭和返回；关闭不改变旧额度。旧 Cursor 的新登录入口转到 Grok／Cursor 共享池，不重复计算订阅。

登录状态保存在该服务独立的 Android WebView 目录。alpha39 打开或返回 App 时自动复用登录，五分钟内使用缓存；停留期间每分钟检查是否需要更新，无需显示网页。重启后先显示上次数据及时间，再检查缓存。退到后台停止自动读取。普通“刷新”可立即重查；会话过期或需要验证时暂停自动读取，并提示点击“官网登录”处理。断开删除该服务的额度和浏览器状态；未完成的登录可用“清除登录状态”删除。三个服务各自隔离，Codex 退出不影响它们。旧连接与手动导入数据继续保留，官网登录成功后替换对应旧凭据。旧 Cursor 卡片需单独断开。

网页验证由用户完成，不绕过验证、不伪装浏览器 User-Agent。部分第三方 SSO 可能拒绝嵌入式 WebView，此时可使用官方页面提供的其他登录方式；不保证所有设备和账号都能登录。WorkBuddy 国内站暂不支持。Android System WebView 必须可用。“官方控制台”在外部浏览器打开，与 App 内登录状态独立。

xAI：在官方控制台创建具有预付账单读取权限的团队 Management key；App 先核对 key 的团队作用域，再读取钱包。Cursor Admin API 需团队管理员可用的 Admin key；不以个人控制台会话替代。公开 API 的模拟测试不代表真实密钥和所有套餐权限已验收。

## 刷新、缓存和隐私

API key 和旧版会话连接在进入或返回应用及前台停留期间自动检查，五分钟内使用缓存，可手动刷新。临时网络错误有限重试并遵守 Retry-After。网页登录连接通过同一服务进程内的无界面 WebView 复用既有登录态，正常缓存五分钟，单次读取至多等待 45 秒。进入前台和前台停留期间自动检查，手动刷新也使用该通道。网页读取依次进行，API 账单可同时独立查询。429 的服务端等待时间对自动与手动刷新均有效；其他临时失败等待五分钟再自动重试，也可手动立即重试，登录或验证失效时暂停自动查询。失败保留上次成功数据与时间。退出前台、进入 App 示例模式时取消自动读取；断开连接会使在途结果失效。没有跨账号合并或后台网页同步。

API key、旧版凭据和解析后的订阅快照使用 Android Keystore / AES-GCM 加密，不保存原始 API 响应。网页登录的 Cookie、本地存储与网页缓存由 WebView 管理在服务独立的应用私有目录中，**不使用上述 AES-GCM 文件加密**，禁用备份；登录秘密不会传回 App 主进程或写入诊断。网页可以正常导航到官方认证提供方，额度脚本只在固定 HTTPS 服务域名执行。断开清理对应 WebView 数据；确认重置不可读订阅存储时也会清理这三项浏览器状态。不影响 Codex 与显示偏好，远端撤销须使用官方控制台。

金额保留十进制精度与币种；未知值不补零。Kimi API 充值余额允许为负，以可用余额判断是否可用。xAI 钱包账本以负数记充值，展示时取相反数，负可用额不取绝对值。Grok 的按量消费上限不是余额。WorkBuddy 是积分，不冒充货币。Cursor 分页数量变化或重复时拒绝汇总，不显示不完整的团队费用。

## 本轮接口依据与验证范围

2026-11-06（北京时间）：在用户已登录的官方页面核对 Kimi、WorkBuddy、Grok／Cursor 的当前字段。Kimi 和 WorkBuddy 同时完成浏览器外的只读 HTTP 查询；没有将真实凭据写入仓库或 Android 测试包。Grok 浏览器内成功，原生请求受到网页验证限制。以下为兼容证据，不承诺服务方私有接口稳定。

- Kimi 会员：`POST https://www.kimi.com/apiv2/kimi.gateway.membership.v2.MembershipService/GetSubscriptionStats`，Bearer access_token、空 JSON、Connect 协议版本 1；解析 `subscriptionBalance` 与有效 `boosterWallets`。[会员规则](https://www.kimi.com/help/membership/membership-update-rules)。
- Kimi Code：`GET https://api.kimi.com/coding/v1/usages`，Bearer；按 `usage` 和 `limits[].window/detail` 解析。[官方 CLI 实现](https://github.com/MoonshotAI/kimi-cli/blob/9ab1286b8fe4e6bcd116949a27ce5e0ac3389c82/src/kimi_cli/ui/shell/usage.py)。
- Kimi API：`GET https://api.moonshot.cn/v1/users/me/balance` 或 `.ai` 同路径，Bearer。[国内官方文档](https://platform.kimi.com/docs/api/balance.md) · [国际余额说明](https://www.kimi.ai/help/kimi-api/api-balance-and-usage)。
- WorkBuddy 国际站：`POST /billing/meter/get-user-resource`，页面自身的 Cookie 与 User-Agent；查询 `p_tcaca` 的有效积分包并遍历分页，解析 `CycleCapacityRemainPrecise`／`CycleCapacitySizePrecise`。[用量规则](https://www.workbuddy.ai/docs/zh/workbuddy/usage) · [自定义模型归属](https://www.workbuddy.ai/docs/zh/workbuddy/From-Beginner-to-Expert-Guide/Function-Description/Model-Configuration)。
- Grok／Cursor：`GET https://grok.com/dashboard/api/dashboard/v2/usage-status?scope=user` 读取共享池与费用；另以只读 `POST get-complat-credit-balance` 获取 `usdRemainingCents`，不计入待退款或待入账金额。[Cursor 用量说明](https://cursor.com/help/models-and-usage/usage-limits)。网页读取与旧快照使用相同字段解析。
- xAI API：`GET https://management-api.x.ai/auth/management-keys/validation` 核对团队；`GET /v1/billing/teams/{team}/prepaid/balance` 读取 `total.val` 的有符号 USD 分。[认证文档](https://docs.x.ai/developers/rest-api-reference/management/auth.md) · [账单文档](https://docs.x.ai/developers/rest-api-reference/management/billing.md)。
- Cursor Admin：`POST https://api.cursor.com/teams/spend`，Basic key + 空密码；`page`／`pageSize` 分页，保留分值小数精度。[官方 Admin API](https://cursor.com/docs/account/teams/admin-api.md)。
- 原有服务依据：[DeepSeek](https://api-docs.deepseek.com/api/get-user-balance/) · [OpenCode Go 历史实现](https://github.com/anomalyco/opencode/blob/d4704347465c1ee63d0c213ed00e648e7f0231c5/packages/console/app/src/routes/zen/go/v1/usage.ts) · [Console API key](https://opencode.ai/v2/docs/console/api/) · [旧 Cursor 控制台响应记录](https://forum.cursor.com/t/cursor-usages-is-not-showing-correct-information/170210)。

alpha38 使用与 App 完全相同的网页读取脚本，在已登录的 Kimi、WorkBuddy、Grok 页面分别验证成功；WorkBuddy 数字 AccountId 与分页已兼容。Cursor 控制台重定向到 Grok。Android WebView 测试拦截全部请求并使用合成登录状态，不能替代真实 Android 账号的完整登录验收。开发者 API 仍未使用真实 key 验收。测试、截图来源与最终 CI 状态见 [本轮记录](screenshots/browser-login-alpha38/README.md)。

## 历史 alpha32 验证记录

2026-11-03 本地验证：

- 228 项 JVM 测试通过，其中新增 24 项解析、HTTP、仓库与操作调度测试，覆盖币种／精度、microcent 换算、缺失值、超额、重定向隔离、凭据头、取消、限流、缓存、失败重连、写盘失败、跨服务隔离和晚到请求；另覆盖不可读缓存恢复、恢复前重试保护可读凭据，以及断开后快速重连／取消不影响凭据删除。
- API 37、arm64 模拟器的 9 项订阅与界面设备测试通过，含新增 4 项订阅测试：取消首次连接不落盘，连接／鉴权失败／断开隔离，加密文件不含明文凭据，示例零请求，英文与日文切换重建。现有界面测试增加 320dp／200% 系统字体的订阅页和四项导航检查。
- debug／release 构建与 lint、55 项发布脚本测试通过。生产代码评审修复后，本地 API 37 全套 83 项设备测试入口执行完成，无失败（包含按系统能力跳过的用例）。
- 远端首轮验证暴露 API 37 冷启动桌面 ANR 与语言测试收尾卡住：测试启动改用唤醒／解锁、在安装 App 前重启一次已知模拟器桌面；语言测试清理使用 Application，并通过系统语言管理恢复。完整远端矩阵仍以 PR／Actions 的最终状态为准。
- 同一 API 37 模拟器使用生产 debug 入口注入损坏文件：重试后仍显示错误，取消清除保留文件，确认清除后错误消失且可重新连接，设置文件散列不变；[恢复确认框](screenshots/subscriptions-alpha32/subscriptions-recovery.png)为 alpha32 debug 故障注入截图，不含真实账号。
- [中文 DeepSeek](screenshots/subscriptions-alpha32/subscriptions-deepseek.png)、[OpenCode Go](screenshots/subscriptions-alpha32/subscriptions-opencode-go.png)、[OpenCode Console](screenshots/subscriptions-alpha32/subscriptions-opencode-zen.png)、[Cursor](screenshots/subscriptions-alpha32/subscriptions-cursor.png)、[English](screenshots/subscriptions-alpha32/subscriptions-en.png)、[日本語](screenshots/subscriptions-alpha32/subscriptions-ja.png)、[320dp／200% 字体](screenshots/subscriptions-alpha32/subscriptions-narrow-large-font.png)均为 alpha32 debug 原生截图、示例数据。

样例与 MockWebServer 测试不能代替真实账号验收。尚未使用真实 DeepSeek／Go／Console／Cursor 凭据验证，不能据此承诺每种套餐与迁移中的工作区都可连接。
