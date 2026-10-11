# 支持范围与已知限制

更新：2026-10-11，当前开发版本 0.1.0-alpha17。本文描述已经取得的证据和未完成的验收，已提供构建预发布，不表示已发布稳定版本。

## 账号与接入

- 首版管理一个 Codex 账号，显示接口实际返回的额度窗口，不根据套餐推测额度。
- 用户已反馈在小米 17 Ultra／Android 17 首次连接真实 Codex 账号成功；这不是对所有套餐、工作区或续期的保证。
- Android 上的完整取消／超时／重新授权／撤销／到期续期，以及多套餐、工作区权限仍待真实账号验收。
- 采用实验性直连适配器，兼容官方开源 Codex 使用的认证与额度协议；并无独立第三方稳定公共 API 的承诺。具体路径与来源见 [接入验证](CODEX_INTEGRATION.md)。
- App 只做额度监控，但授权凭据权限可能广于读取额度；本地退出不等于撤销远程授权。处理方式见 [隐私说明](PRIVACY.md)。

## 刷新行为

- 点击组件刷新会立即尝试获取，超过 8 秒交给后台任务续办；刷新成功后更新所有组件。
- 手动刷新仍遵守服务端 Retry-After，不承诺绕过限流；失败保留最后成功快照和时间。
- 网络、系统省电、Doze 和厂商策略会延后后台刷新。每轮持续失败最多尝试三次，之后等待下一周期或新的手动操作。
- 系统强制停止期间不承诺更新；重新打开 App 后恢复调度。锁后台、自启动和电池优化设置见设置页的分品牌引导。
- 没有真实数据时不显示假额度；示例模式需用户主动选择并标明示例。

## 设备与已知问题

| 项目 | 当前证据与限制 |
| --- | --- |
| Android 10／12／15／16 | alpha16 [六版本 CI](https://github.com/tsonglew/QuotaPal/actions/runs/38037325156) 全部通过，含组件、生命周期、改时和网络探针；不代替 OEM 与长期观察。 |
| Android 17 | AVD 主版本元数据修正后，云端 x86_64 37.0／37.2 完整设备测试与探针通过，实际页面大小分别为 4096／16384；本地 ARM64／TalkBack 有独立证据。此结果不代替真机兼容性。 |
| 小米 17 Ultra／Android 17 | 首次真实连接成功；杀后台后的自动更新、组件手动刷新修复及菜单路径尚待用户真机复核。 |
| Samsung 与其他厂商桌面 | 已实现分品牌引导；没有对应完整真机验收，不能承诺所有固件菜单相同。 |
| 长期运行 | 48 小时后台观察、连续七天自用未完成，后台稳定性和耗电仍待验证。 |
| 发布与升级 | [alpha16 自动签名运行](https://github.com/tsonglew/QuotaPal/actions/runs/38037985183) 已通过完整 CI，下载后证书／哈希／版本／来源及对齐校验成功。[正式 alpha15→alpha16 覆盖升级](VALIDATION_ALPHA16.md) 已在 API 31 合成账号上验证缓存、设置、组件及凭据恢复；真实账号／OEM 仍待验收；alpha17 [自动构建预发布](https://github.com/tsonglew/QuotaPal/releases/tag/build-38109285393-1) 已完成六组 CI 与签名／哈希／来源核验，开发 APK 不应当作稳定发布版。 |

发现刷新问题时记录 App 版本、系统／品牌、最后成功时间、是否清理或强停、网络与电池设置即可；不要发送 token、授权码、账号缓存或未脱敏的原始响应。排查入口见 [使用帮助](RELEASE_MATERIALS.md)。

## 本机诊断报告

复现问题后，进入设置 → 问题排查 → 预览诊断报告，核对后通过“分享”发送给维护者。详细事件只保留最近 200 条，另保留最近 168 个小时的汇总统计；清除应用数据或设置中的“清除诊断记录”可删除。退出账号不会清除这些不含账号信息的事件。分享后由接收应用管理报告副本。

报告 schema=1，每行包含 UTC 时间、事件名称和位置参数。设备报告不包含品牌、设备标识、令牌、账号、额度值、接口响应或截图。UI_STATE 表示 Compose 状态观察，WIDGET_END 表示组件更新请求完成，均不能证明 Launcher 的最终像素正确；显示问题仍需补充遮挡个人信息的截图。

| 事件 | 参数顺序／含义 |
| --- | --- |
| UNCAUGHT_EXCEPTION | 类别（其他0、内存不足1、栈溢出2）、是否主线程；仅本机标记，保留系统原异常处理 |
| HTTP_START / HTTP_END / HTTP_NETWORK_FAILURE | 类别（额度0、授权1）／类别、HTTP状态、响应头耗时毫秒／类别、请求是否取消 |
| SNAPSHOT_SAVED | 成功快照已写入缓存与设置；包括首次连接，不含示例模式 |
| REFRESH_START | force：1 手动强制，0 普通刷新 |
| REFRESH_END | SyncResult 序号、耗时毫秒（含等待锁）、FailureKind 序号（-1 无）、快照年龄秒（-1 无） |
| SKIP_BACKOFF | 距允许重试的秒数 |
| SKIP_RECENT / SKIP_FRESH / SKIP_AUTH / SKIP_ACCOUNT / SKIP_DEMO | 短时间重复／缓存未过期／需授权／无账号／示例模式 |
| RENEW / REFRESH_CANCELLED | 发起令牌续期／正在执行的请求取消 |
| WORK_START / WORK_END | 重试次数（从 0 开始）／SyncResult 序号 |
| SCHEDULE / CANCEL_WORK | 目标间隔分钟／取消任务请求 |
| WIDGET_START / WIDGET_END / WIDGET_ERROR | 更新组件请求开始／完成／异常 |
| UI_STATE | 页面（额度0、组件1、设置2）、已初始化、已连接、同步中、有快照、错误序号、宽dp、高dp、字体比例×100、Android uiMode、显示剩余、主题（系统0、浅色1、深色2） |
| WIDGET_STATE | 宽dp、高dp、字体比例×100、有快照、同步中、显示剩余、主题（系统0、浅色1、深色2） |
| FOREGROUND / MANUAL_REFRESH / WIDGET_CLICK | 前台刷新入口／应用手动刷新／组件点击刷新 |
| APP_START / UI_ERROR | 应用进程启动／界面操作或偏好读取异常 |

SyncResult：SUCCESS=0、RETRY=1、AUTH_REQUIRED=2、DEFERRED=3、NO_ACCOUNT=4。FailureKind：AUTH=0、FORBIDDEN=1、LIMITED=2、NETWORK=3、SERVER=4、PROTOCOL=5、STORAGE=6。事件位置参数中的布尔值为 0/1。后台未出现 WORK_START 时，结合报告中的网络、省电、电池优化和系统后台受限状态检查系统调度；出现跳过事件时按原因检查缓存或退避。日志写入失败不会阻断额度刷新。

报告头中的 `powerSave`、`idle`、`batteryExempt`、`backgroundRestricted` 使用 true/false，表示生成报告时的系统状态；`networkValidated=null` 表示没有读到当前网络能力。它们不是历史记录，不能用当前状态反推故障当时的状态，也不能读取 OEM 自启动／任务锁定等私有开关。

| 现象与事件 | 下一步核对 |
| --- | --- |
| 缓存很旧，目标周期附近没有 WORK_START | 核对系统限制、网络和后台任务状态；仅凭 ENQUEUED 或事件缺失不能锁定原因 |
| HTTP_END 0 200 后没有 SNAPSHOT_SAVED | 查看 REFRESH_END 的解析／存储错误；HTTP 200 不等于有效快照已保存 |
| 已有 SNAPSHOT_SAVED，但组件显示旧时间 | 对照 WIDGET_START／WIDGET_END／WIDGET_ERROR、组件状态和实际截图，检查更新请求及 Launcher 显示 |

合成账号的正式 APK 外部 Doze 实验中，[恢复事件原文](diagnostics/power-api31/doze/app-recovery-events.txt) 与服务计数、缓存及桌面截图对应：WORK_START → HTTP_END 0 200 → SNAPSHOT_SAVED → WORK_END → WIDGET_END。此样本说明如何关联已有证据；最近 200 条事件可能已滚动丢弃，进程被系统终止也可能没有结束事件，不能把缺少某条事件直接等同于请求卡死。

### 48 小时与 7 天观察

诊断报告提供最近 168 个 UTC 小时桶，按事件时间裁剪，无事件小时不生成空行。统计跨应用进程保留；清除诊断记录会一起清除统计。退出或切换账号不分段、不记录账号，因此观察期间更换连接需另行注明。系统时钟回拨时丢弃未来桶，不据此声称记录覆盖完整观察期。

- usageRequests／authRequests：启动的应用 HTTP 调用次数，授权轮询也计数；跳过刷新不计请求，调用次数不等同于服务端实际收到的次数。
- httpNon2xx：收到的非 2xx 响应次数。设备授权等待中的 403／404 可能正常，不等同于登录失败。
- networkFailures：OkHttp 失败或响应体读取 IO 失败，包含主动取消；查看详细事件的取消标记判断。
- snapshotsSaved／lastSavedAt／maxSuccessGapSeconds：真实成功写入次数、最后成功 Unix 秒、与前次成功之间最大的非负间隔。授权首次读取也纳入；示例模式和缓存命中不纳入。间隔只覆盖仍保留的观察数据，不等同于最大后台调度延迟。
- 后台任务报告列出未结束周期／手动任务的状态与已重试次数，最多各 5 条；状态查询超时单独显示 unavailable，不阻断其余诊断。ENQUEUED 本身无法证明受哪个具体系统约束影响。

开始自用观察时先清除诊断记录，记录开始时刻、机型／Launcher、组件个数、目标间隔以及系统电量。48 小时后先导出报告，再记录结束电量及设备电池页面显示的应用耗电；七天结束时再次导出。应用不自行推算耗电，也不将 7 天统计能力视为已完成 7 天稳定性验收。

崩溃记录仅标记 Java／Kotlin 未捕获异常的大类与是否主线程，不保存消息或堆栈、不自动上传。原系统异常处理继续执行；原生崩溃、ANR、系统直接杀进程或存储不可写可能没有标记，不能以没有事件证明未发生崩溃。
