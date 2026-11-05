# ChatGPT API 订阅赠送额度（alpha36 源码）

在总览 → Codex 明细展开「ChatGPT API 赠送额度」查看领取资格、每周期赠送金额、本周期可领取金额、到期时间及查询时间。使用已有 Codex 登录，按需查询，五分钟缓存；手动刷新遵守服务端等待时间。失败保留旧值并标记缓存，不影响 Codex 额度、Token 统计或重置卡。

这不是 API 剩余余额。账单福利预览没有返回消费后的余额；应用不从赠送金额、组织费用或消费上限反推余额，也不将缺失值显示为零。卡片提供 ChatGPT 账单入口，领取、绑定及余额核对在官方页面完成。

## 接口与验证边界

- 官方规则：[ChatGPT API credits](https://developers.openai.com/api/docs/guides/chatgpt-api-credits)。订阅资格、金额和周期以官方实时结果为准，应用不按套餐名称硬编码金额。
- 只读路径：`GET /backend-api/product-account-links/accounts/{account_id}/benefits/chatgpt_api_credits_promo?destination_type=api_org`。
- 2026-11-06 在已登录 ChatGPT Billing 页面核对了只读查询，以及 `eligible: false`、`ineligibility_reason: not_qualified`、`offer: null` 的响应结构。没有领取、绑定或修改账单。
- 字段依据该页面公开客户端：`offer`／`benefit` 的 `period_credit_amount_usd`、`claimable_credit_amount_usd`、`currency`、`cadence`、`expires_at`。实际领取成功后的账号和 Business 账号尚未验证，测试金额全部为合成数据。
- 这是非公开、实验性兼容接口，不是 OpenAI 公共 API 的余额接口。网页请求可能附带浏览器验证；尚未验证 Codex OAuth 凭据在此端点的实际兼容性。403、验证要求及未知结构显示不可用，不绕过验证、不自动登出、不假定零余额。真实账号的 App 自动读取仍待验收。
- 只有 GET 请求；不实现领取、组织绑定／解绑或取消福利。凭据留在现有加密凭据库，福利快照存入本机非加密数据库；切换账号和退出清理或隔离缓存。

解析测试覆盖未符合条件、按比例金额、已有福利、未知结构、币种、负数、异常金额与过期时间。仓库测试覆盖并发、缓存、取消、账号切换、退出、存储失败及 Retry-After；原生界面测试使用示例数据。
