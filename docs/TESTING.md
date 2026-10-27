# Quodex 验证记录与自用步骤

自动化结果以 [Android CI](https://github.com/tsonglew/Quodex/actions/workflows/android.yml) 为准；阶段进度见 [PROGRESS.md](PROGRESS.md)，完整验收见 [DEVELOPMENT_CHECKLIST.md](DEVELOPMENT_CHECKLIST.md)。

## alpha21 Android UI/UX 优化

2026-10-22，按 [官方指南与设计取舍](UI_UX.md) 调整页面、主题和控件。本地 `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` 成功：152 项 JVM 测试、0 失败；lint 0 错误、43 项既有警告；App 与测试 APK 生成成功。

API 31 专用模拟器通过 17 项不同的原生回归：

- `UiExperienceTest` 手机 3 项通过（12.345 秒），验证两个标签独立滚动恢复、30 天周期保持、固定刷新入口、开关标签与状态语义、至少 48dp 的触摸范围、点击文字切换，以及真实系统 200% 字体与 320dp 容器中的文字布局和主要操作。字体设置结束后恢复。
- 宽屏专项在普通手机配置按条件跳过，随后将专用模拟器临时调整为 160dpi，在实际宽窗口中单独运行通过（7.494 秒）；检查侧边导航的位置、纵向排列与内容最大 640dp，之后恢复密度。
- 最终 AppFlow 6 项、TokenUsage 3 项、QuotaStatus 3 项、真实全局开关／三个小组件联动 1 项全部通过（35.334 秒）。

已查看最终浅深色首页、设置、320dp／200% 字体的趋势与更新页面，以及宽屏额度／设置截图。测试捕获到品牌标识在大字体下继承正文行高而裁切，已修正标识字号和行高；首次切换标签出现复用的旧渲染画面，已恢复按标签隔离渲染节点，同时保留可保存状态，并以新截图及回归核对。

Compose 1.8.2 的 SimpleText 语义接口按原最大宽度重建 `MultiParagraph`，同时保留文字实际测量宽度，直接读取 `didOverflowWidth` 会将正常的短文本误报为溢出。依据 Google Maven 官方 `ParagraphLayoutCache` 源码，测试改为逐行实际文字宽度与测量宽度比较，仍检查高度溢出、最大行数和省略号；原始诊断全部保留。截图在原生渲染帧完成后捕获，早期失败／旧画面不作为通过证据。

本地证据位于 `artifacts/ui-ux-validation/`：最终构建 `gradle-layout-fix.log`、手机专项 `ux-device-final.txt`、宽屏专项 `wide-device-test.txt`、既有回归 `existing-device-final.txt`、`contrast.json`、`ui-layout-diagnostics.txt` 及 `screenshots/`。本轮未运行远端 CI、真实 TalkBack 人工体验或 OEM 验收，改动尚未发布。

## 自动化验证

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
bash scripts/lifecycle_smoke.sh
# The following probes change system state: owned test emulators only.
bash scripts/system_time_smoke.sh
bash scripts/network_smoke.sh
bash scripts/power_smoke.sh
```

- JVM：解析单／多窗口、缺失／非法百分比、重置时间、账号隔离、HTTP 错误、退避、并发请求、续期与退出时的旧响应。
- Android 页面：进入示例、切换标签、深色主题、退出清理，导出浅深色截图。
- Android 存储：Keystore 加密往返、密文篡改拒绝、凭据删除和备份关闭；组件配置互不覆盖。
- Android 组件：使用 debug 专用原生 AppWidgetHost，渲染真实 Glance RemoteViews，检查 140×150、280×150、140×230 dp，退出后切换到未连接状态。
- 可选额度回归：仅周额度、无窗口且可用、未提供周期额度、用量未知、服务端受限；检查解析和缓存替换、Compose 说明及三种真实组件尺寸，确保不虚构百分比且提示与更新时间完整可见。
- 2×1 原生组件：验证独立入口的 2×1 网格元数据，130×70、140×70、280×70 与横屏 260×50 dp；覆盖浅深色、已用／剩余、无周期、未知用量、受限和退出，逐项检查所有文字可见且不省略。与标准组件同时绑定时检查共享更新和独立设置。
- 桌面额度图标：`DesktopIconContentTest` 覆盖周期选择、剩余语义、0／100／小数、非法值、附加窗口隔离、示例／旧数据／受限／退出及关闭；`DesktopQuotaIconDeviceTest` 只在受控模拟器执行，检查 Launcher 确认添加、同 ID 更新、后台 worker、桌面实际点击、退出清理、设置切换和圆形裁切，保存 `desktop-icon-*` 截图。OEM Launcher 仍须真机验收。

组件测试临时通过系统 shell 授予绑定权限，结束时撤销。测试宿主仅包含在 debug 变体中，未导出，不进入 release。实现依据 [Android widget host 文档](https://developer.android.com/develop/ui/views/appwidgets/host) 和 [AOSP appwidget shell 命令](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/cmds/appwidget/src/com/android/commands/appwidget/AppWidget.java)。宿主测试不能替代真实 Launcher、系统选择器和 OEM 验证。

小米触摸修复的定向集合现为 16 项：`WidgetOpenAppDeviceTest` 7 项、`WidgetRefreshActionDeviceTest` 5 项，加既有 4 项组件渲染回归；TalkBack 另有 2 项显式专项。打开与刷新均注入真实 DOWN/UP；API 31+ 还检查实际动作节点的类精确为 `TextView`（不能用会同时接受 Button 的子类判断），同组件的动作 ID 有效且唯一，动作区域不重叠。新增 slim 页脚时间中心点击以已连接的合成账号验证打开应用且不新增 HTTP 请求，并预先解除最近尝试节流，避免误刷新被示例模式或节流掩盖。打开、刷新及 TalkBack 夹具显式初始化和清理自己创建的组件偏好，不依赖旧模拟器中同 ID 的残留设置。API 31 覆盖基础资源、API 33+ 覆盖限定资源，API 29/30 继续验证 Glance 的旧版模拟路径；资源替换后的通过结论须来自新 APK 实跑，下面的 v3 记录不覆盖这次替换。

CI 产物 `android-build` 含 debug APK、单元测试与 lint 报告；设备矩阵配置 API 29、31、35、36、37.0 与 37.2／16KB；配置覆盖不等于全部通过，最新结果须核对 CI gate。`android-device-tests-api-<API>` 各含对应设备测试报告和截图，保留 14 天。不上传凭据或真实账号数据。模拟器矩阵不替代 OEM Launcher、真实授权与连续自用。

## alpha23 桌面额度图标

2026-10-28 基于 master `8b064c5` 接入新的流线 Q 和深海青界面。干净构建通过 165 项 JVM 测试、debug lint、App 和设备测试 APK 构建。图标的 10 项单元测试覆盖周期与剩余语义、有效边界、缺失／非法数据、示例／缓存／受限／退出和关闭。

API 29 的桌面图标、AppFlow、Worker 与唯一周期任务共 13 项用例均取得通过证据：首轮添加确认误选了同名标题，修正测试选择器后，实际添加、背景更新、真实触摸打开及退出清理独立重跑通过。另有 3 项 UI 回归通过，覆盖整行开关、滚动状态和 200% 字体；宽屏专项因当前窗口不足 600×480 dp 按条件跳过。API 37 的 4 项图标专项全部通过，包括 Pixel Launcher 的系统确认、同 ID 更新、真实桌面触摸和无组件时的后台策略。

[图标边界与状态样例](screenshots/desktop-icon-overview.png)和[原生设置预览](screenshots/desktop-icon-settings.png)均来自本次模拟器截图，只包含合成数据。此结果不代替 OEM 桌面、真实授权或长期自用验收；远端完整 CI 和正式签名发布以对应 PR／Release 结果为准。

## 首次自用验收

先用示例检查页面和组件，再连接自己的 Codex。当前接入处于实验阶段，兼容官方开源客户端协议；真实 Android 授权和公开发布适用范围仍需确认。不要复制电脑上的 `auth.json` 到手机。

设备码登录需要先在 ChatGPT 的安全设置中启用；工作区账号可能需要管理员在工作区权限中开启。此要求来自 [官方认证说明](https://learn.chatgpt.com/docs/auth#preferred-device-code-authentication-beta)。

| 步骤 | 应看到的结果 | 记录 |
| --- | --- | --- |
| 安装 debug APK，首次打开 | 未连接欢迎页，无虚构账号数据 | 待真机 |
| 点击“先看看示例” | 明确标记示例数据；剩余 62%／36% | 待真机 |
| 切换已用／剩余与浅深色 | 数字和分段条语义一致 | 待真机 |
| 点击连接，取消一次 | 关闭登录流程，无新账号残留 | 待真机 |
| 再次连接，打开 OpenAI 页 | 在系统浏览器授权，不在 App 输入密码 | 待真机 |
| 完成设备码授权 | 自动返回可刷新额度，账号／工作区正确 | 待真机 |
| 对照同账号官方客户端 | 窗口、百分比和重置时间相符，记录对照时刻 | 待真机 |
| 使用无短周期窗口的账号 | 保留周等长期额度，明确提示未提供短周期额度；主界面与组件一致 | 待真机 |
| 账号未返回任何周期窗口 | 有说明文字，不显示空卡片或虚构百分比；服务端确认可用时显示当前可用 | 待真机 |
| 经系统选择器添加组件 | 配置可取消、保存成功后出现在桌面 | 待真机 |
| 放置 3 个组件并缩放 | 各自主题可不同，共享同一更新时间与额度快照 | 待真机 |
| 断网后手动刷新 | 保留上次数据与成功时间，显示失败状态 | 待真机 |
| 恢复网络再刷新 | 获取新快照，App 与组件同步更新 | 待真机 |
| 退出并清除数据 | App 和全部组件进入未连接，后台同步取消 | 待真机 |
| 重新登录、进程回收、重启 | 仅恢复当前账号缓存，联网后正常同步 | 待真机 |
| 令牌实际到期后继续使用 | 续期成功，且无重复续期／账号混用 | 待真机 |

设备记录只包含机型、Android 版本、Launcher、测试时刻、结果和脱敏现象；不记录登录码、token、账号邮箱或完整服务响应。

## 长时间观察

本地 JVM 回归覆盖连接提交期间的取消、账号替换、退出竞争、部分存储写入失败及回滚失败；同时检查诊断汇总的小时边界、过期、进程重建后的停更时长、时钟回拨和只读导出。它们不代替真实授权服务或设备长期观察。诊断汇总字段及解读见 [支持说明](SUPPORT.md#48-小时与-7-天观察)。

2026-10-10 自用设备反馈：小米 17 Ultra，Android 17（用户更正后的报告），当前 APK 已成功连接真实 Codex 账号。此反馈仅证明用户完成首次连接，不作为额度对照、续期、退出／重连、OEM 组件操作或连续使用时长的通过证据。

48 小时后台观察和连续 7 天自用必须实际等待完成，不能用模拟器通过替代。记录实际刷新间隔、请求次数、失败与恢复、耗电和崩溃；Doze 与系统省电下允许刷新延迟，更新时间必须真实。

强制停止后，以重新打开 App 能恢复为验收条件。Pixel/AOSP、Samsung、小米 Launcher 以及最低 Android 版本仍需分别执行。公开发布前继续完成签名、升级迁移、渠道与隐私政策验收。

## 外部生命周期回归

`lifecycle_smoke.sh` 使用测试包的合成账号拦截器，seed 写入真实 Keystore／Room 并绑定平台组件，外部停止进程后 restore 检查缓存时间、账号、同一组件 ID／独立设置、组件重新显示和唯一周期任务。普通测试套件跳过该两阶段测试，CI 在普通套件通过后单独执行，保存 lifecycle-seed.txt／lifecycle-restore.txt；am instrument 的退出码不能证明通过，脚本还要求 OK (1 test)。

仅在测试模拟器上使用 `LIFECYCLE_RESTART=reboot` 执行实际设备重启，或先装旧版 debug APK、再用 `LIFECYCLE_RESTART=upgrade LIFECYCLE_APK=/absolute/path/new.apk` 验证覆盖升级。升级断言要求版本号实际增加，不接受同版本重装作为版本迁移证明。`LIFECYCLE_OUTPUT_DIR` 可改证据目录。测试结束清除合成账号及测试组件；失败后如需恢复可重跑完整 harness。真实账号和 OEM 验证仍单独执行。

alpha10 增加 Worker 设备回归：合成离线／超时异常、503 与 429，检查原成功快照不变、每轮最多 3 次、Retry-After 阻止额外请求和新尝试恢复。此回归验证传输故障处理与 Worker 返回值，不代替设备实际断网、Doze 或 OEM 后台限制。

alpha11 的 Android 17 CI 使用 `scripts/native_device_tests.py` 安装两 APK 并执行完整 runner，规避已观察到的 ddmlib 安装失败。原始报告保存在 `screenshots/native-instrumentation.txt`；至少 18 项实际通过、开始／结束状态配对及成功完成码是必要条件，Shell 退出 0 不代表测试通过。其他 API 继续使用 Gradle connected 流程。大字体失败时保存实际 View 几何及截图，方便定位旧系统占位布局问题。

alpha12 新增 `scripts/system_time_smoke.sh`：仅在自建测试模拟器执行真实改时探针，检查时区／夏令时／跨天及回拨刷新，finally 恢复时间与自动设置。两个系统时间广播属于 [Android 隐式广播例外](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions)，接收器只重绘缓存；不使用每分钟广播唤醒后台。

`scripts/network_smoke.sh` 显式关闭自建模拟器的 Wi-Fi 与移动数据，要求 OS 从有效互联网变为无网络，再恢复原连接。检查真实 WorkManager 约束与调度：离线不执行请求、成功缓存保留，重连后只请求一次。用量响应由测试 runner 拦截，不把合成凭据发到外部。该探针普通套件默认跳过，CI 在系统时间探针后单独运行。仅“有 activeNetwork”不算恢复，必须满足 NET_CAPABILITY_VALIDATED；本地受代理限制的宿主须先配置其测试网络并在结束后恢复。

TalkBack 专项仅在装有 Google TalkBack 的 API 37 测试模拟器显式执行：`adb -s emulator-5580 shell am instrument --no-hidden-api-checks -w -e class com.tsonglew.quodex.TalkBackDeviceTest -e talkbackProbe true com.tsonglew.quodex.test/com.tsonglew.quodex.QuotaTestRunner`。会临时启用真实 TalkBack，分别检查默认字体下额度文本的焦点／探索，以及 200% 字体下标准／紧凑组件的双击刷新和共享请求，保存截图后恢复设置。探针反射调用平台测试 API `injectInputEventToInputFilter`，缺失或不可访问直接失败；默认字体探索还必须收到 `TYPE_TOUCH_EXPLORATION_GESTURE_START`。普通 `injectInputEvent` 会跳过无障碍输入过滤器，不能作为 TalkBack 手势证据；此前基于该入口的双击通过记录应按普通注入刷新理解，见 [证据校正](VALIDATION_XIAOMI_WIDGET_TOUCH.md)。普通套件按条件跳过该显式探针。

2026-10-20 的固定 v3 APK 在 API 37 两项专项均通过（60.637 秒），普通字体标准／slim 探索焦点包含 62%；200% 字体先真实探索刷新入口建立 TalkBack 焦点，再以明确手指类型、受系统双击时限约束的输入触发刷新点击事件，确认各一次请求、仓库额度和可访问摘要更新到 83%。同一 APK 的六项打开应用测试通过（63.617 秒），证据在 `artifacts/xiaomi-alpha18/emulator-widget-fix-v3/`，结束字体恢复 1.0、无障碍服务恢复关闭。此结果证明输入处理和可访问内容，未录制实际语音；`talkback-slim-updated (3).png` 捕获时仍绘制 62%，因此不把该帧作为已显示 83% 的视觉证据。


## 省电恢复探针

`scripts/power_smoke.sh` 仅用于受控模拟器；API 29 CI 在网络探针后执行。它在隔离 Wi-Fi／移动网络的合成夹具生命周期内切换深度 Doze、系统省电及 RUN_ANY_IN_BACKGROUND 限制，确认 JobScheduler 的 `readyNotDozing=false`／`readyNotRestrictedInBg=false`，退出限制并替换进程后核对缓存、组件与唯一周期调度恢复。原 forced-idle、deep 开关、屏幕、电池、省电、app-op 和网络状态在结束时恢复。它不证明 Doze 单独引起的延迟，也不替代 48 小时的请求次数和耗电观察。

## 当前验证边界

alpha14 的刷新存储故障及同进程限流保护由 JVM 故障注入覆盖；七项页面／手动刷新／Worker 设备回归通过。Worker 限流夹具使用短 Retry-After 并等待真实截止时间，不再靠清除磁盘截止字段冒充过期。

Android 17 专用 CI 使用 SDK 稳定模拟器并安装 Linux 运行依赖，保留 API 37.0／37.2 系统镜像与完整原生 runner。AVD 根 ini 明确使用整数主版本 target=android-37，系统镜像路径不变，使用默认 software 图形路径。曾测试的 37.1.11 固定及 Vulkan composition 强开已撤回；失败对照见 [alpha14 验证](VALIDATION_ALPHA14.md)。

[运行 38007906590](https://github.com/tsonglew/Quodex/actions/runs/38007906590) 的构建、单元测试、lint、产物校验、六版本设备矩阵与统一 gate 全部通过。Android 17 普通／16KB 两组各 18 项实际普通测试及四个独立探针通过；启动后实际断言 guest SDK=37，16KB job 的 PAGE_SIZE 必须等于 16384。保留三组件真实触摸刷新、单请求与刷新中反馈断言，以及尺寸换算后 provider 实收尺寸检查。此证据不代替 OEM／长期运行／真实认证生命周期或正式签名发布验收。

## 模拟器桌面弹窗隔离

master 12c557b 的 [38032639377](https://github.com/tsonglew/Quodex/actions/runs/38032639377) 在 API 36 的真实组件点击测试失败。报告中刷新按钮实际可见、具有点击容器且布局完成，但所有节点 windowFocus=false；[失败截图](screenshots/emulator-quickstep-anr-api36.png) 明确显示系统 Quickstep 无响应弹窗挡住测试，尚未注入点击。这与先前 RemoteViews 手势竞争不同，保留失败记录，不把本轮算作成功。

master f5edf96 的 [38034462993](https://github.com/tsonglew/Quodex/actions/runs/38034462993) 又在 API 37.0 失败。[截图](screenshots/emulator-pixel-launcher-anr-api37.png) 明确显示 Pixel Launcher isn't responding，系统日志确认默认桌面 com.google.android.apps.nexuslauncher 输入超时；原 Quickstep 专用处理没有匹配此变体，窗口持续失去焦点，尚未注入点击。

测试候选只在模拟器 ro.kernel.qemu=1、系统包 android 的活动窗口、完整标题与系统默认 HOME 对应时，保存截图并关闭一次该弹窗：com.android.launcher3 对应 Quickstep isn't responding，com.google.android.apps.nexuslauncher 对应 Pixel Launcher isn't responding。桌面必须带系统包标记，随后继续原按钮可见性与真实点击断言。重复弹窗、未知弹窗及 Quodex 自身 ANR／崩溃不在处理范围，仍阻断测试。查询声明仅在 debug manifest，处理代码仅在 androidTest，不进入正式 APK。此处理需新的云端矩阵实际验证，不宣称已修复系统桌面的 ANR。

## 真实 WorkManager 退避探针

`scripts/scheduled_retry_smoke.sh` 显式运行 `ScheduledRetryDeviceTest`，要求模拟器、debug 包和 OS 已验证的网络。使用真实 `SyncScheduler.refresh` 与 WorkManager：合成 503 连续失败，分别检查实际请求间至少约 30／60 秒的指数退避，第三次进入 FAILED，终止后不增加请求且保留原缓存；新的用户刷新必须创建新任务、只请求一次并成功替换快照。请求时刻由测试拦截器记录单调时钟，不修改时钟、持久化限流字段或 runAttemptCount，也不调用 TestDriver。

普通套件默认跳过；CI API 29 在原省电恢复探针后单独执行并保存 `scheduled-retry-probe.txt`，仍需明确 OK (1 test) 与 instrumentation 成功状态。宿主执行有超时；结束强停测试应用，失败时清除可能残留的合成凭据。此项证明真实调度的有限重试，不代替保持网络时的 Doze／省电限制观察及长期验收。

2026-10-10 本地 API 31 实际通过（112.842 秒）：两段请求间隔为 30,237／60,127 ms，第三次进入 FAILED，新用户请求成功。证据：[runner 原文](diagnostics/scheduled-retry-probe-api31.txt)、[实际时长](diagnostics/scheduled-retry-timing-api31.txt)。PR #18 的 [完整 CI](https://github.com/tsonglew/Quodex/actions/runs/38042885098) 已通过六组设备与 gate，API 29 独立探针 112.766 秒成功，实际退避为 30,098／60,098 ms（[runner](diagnostics/scheduled-retry-probe-api29.txt)、[时长](diagnostics/scheduled-retry-timing-api29.txt)）。E03 的省电场景仍待完成。

## 保持联网的外部后台观察

进程内 instrumentation 会保留 WorkManager 的 GreedyScheduler；本地 API 31 实验中，即使 JobScheduler 显示 `readyNotDozing=false`，活着的测试进程仍执行了到期任务。因此该方式不能证明应用退出后的系统调度延迟，不能只检查 JobScheduler 字段便宣称通过。

`scripts/upgrade_lab_server.py` 支持 `--direct-tls-port 9444`，与原 `--port 9443` 的控制接口共享合成账号和请求计数。直接 TLS 只接受 `auth.openai.com`／`chatgpt.com` 的固定协议路径，拒绝未知 Host 和 `/lab/*` 控制路径；所有模式均不转发外部请求。该入口用于自建、可 root 的一次性模拟器，不修改正式 APK 的证书校验或业务代码。

实验准备流程：

1. 确认目标 serial、AVD 名称和 `ro.kernel.qemu=1`，保存原 hosts、deep Doze、屏幕、电池、省电与 app-op 状态。使用新建实验目录生成临时 CA；保留原始 hosts 备份，不能用上次实验的映射覆盖它。
2. 仅在该模拟器安装临时系统 CA，将两个协议域名映射到 loopback，重启使信任与 DNS 生效。通过指定 serial 的 `adb reverse tcp:443 tcp:9444` 接入本机服务；保持全局 HTTP 代理关闭，核对当前默认网络实际具有 VALIDATED。
3. 安装已核验哈希／固定签名的正式 APK，用合成账号连接，通过界面选择 15 分钟周期并添加实际桌面组件。等首次周期完成后，回到桌面并使用 `am kill` 回收后台进程，确认 PID 消失；不能用会暂停后台任务的 force-stop 代替。
4. 外部记录服务请求计数、只读数据库副本、唯一 WorkSpec 的周期／入队时间／尝试次数、JobScheduler 约束和网络状态。限制期间实际等待完整周期，不能修改时钟、数据库或强制执行 job。解除限制后等待系统自行启动刷新，核对原任务、成功缓存时间和组件恢复。
5. 结束时恢复系统限制、原 hosts，移除临时 CA 与 adb reverse，并清除模拟器中的合成账号。对 writable-system 镜像重启复核恢复结果，再关闭模拟器，避免旧映射在下次启动重新出现。

2026-10-10：直接 TLS 下正式 alpha16 已完成实际连接和额度读取，默认网络保持 VALIDATED；协议测试覆盖认证／401 续期／读取、共享计数、未知域名及控制路由拒绝、CA 信任。随后外部 Doze 观察实际通过，详见下方证据；系统省电与后台受限仍待独立验收，E03 保持未完成。

`scripts/background_power_probe.py` 将上述观察整理为外部脚本，当前解析器限 API 31。准备好专用模拟器和合成账号后，每次选择 `doze`、`saver` 或 `restricted` 中一个模式，提供明确的 adb 路径、serial、AVD 名称与新输出目录。例如：

```sh
python3 scripts/background_power_probe.py \
  --adb .tools/android-sdk/platform-tools/adb \
  --serial emulator-5556 --avd quodex-release-upgrade-31 \
  --mode restricted --output /tmp/quodex-background-restricted
```

脚本拒绝已有 Doze／省电／后台限制、非 loopback 的协议域名、缺少 TLS reverse、真实账号缓存或非 15 分钟唯一周期；每个模式实际等待 16 分钟，保留网络，每 30 秒检查请求上限。Doze／后台受限要求零请求及缓存时间不变，系统省电允许正常周期请求但禁止请求激增；解除限制后要求自动得到更新的成功缓存并保留任务和组件。输出只保存所需数据库元数据，临时数据库副本自动删除。系统限制在 finally 恢复，APK／临时 CA／hosts 等实验环境仍须按上述流程单独清理。

该脚本已通过五项网络／hosts／组件解析及恢复失败回归，全部 35 项脚本测试成功；实际并发实验拒绝检查及运行中模拟器的只读快照核对通过。基线与最终 crash buffer、退出原因和最近 ANR 原文单独保存，需要核对是否出现 Quodex 新增异常，不能把请求／缓存断言成功当作无崩溃证明。三个模式的完整运行尚未验收，不能据此勾选 E03。

### API 31 外部 Doze 实测

正式 alpha16（源 `6ac605a`，完整 APK 哈希和证书见 [汇总](diagnostics/power-api31/doze/summary.json)）在专用模拟器、合成账号、直接 TLS 下完成约 977 秒观察。后台进程通过 `am kill` 回收，保持有效默认网络；任务周期 900,000 ms、首次周期已结束，到期后 JobScheduler 仍显示 `readyNotDozing=false`，新增请求为零，成功缓存时间保持不变。JobScheduler 的 earliest 已过去约 2 分 15 秒；底层 Wi-Fi 仍 VALIDATED，但该应用 UID 的网络被标记 REASON_DOZE，符合系统空闲限制，不能将这里的 CONNECTIVITY 未满足误读为实验主动断网。解除 Doze 后未打开 App 或强制调度，原 WorkSpec 自行运行一次，period_count 从 1 增至 2，请求数 1→2，缓存 fetchedAt 从 1791627337 更新为 1791628489。

[观察原文](diagnostics/power-api31/doze/observer.txt)、[到期快照](diagnostics/power-api31/doze/doze-due.json)、[约束](diagnostics/power-api31/doze/doze-due-constraints.txt)、[恢复快照](diagnostics/power-api31/doze/recovered.json) 均保留。恢复后桌面仍为剩余 62%，[实际截图](diagnostics/power-api31/doze/recovered-widget.png) 的更新时间为 18:34；crash buffer 为空，系统 lastanr 显示本次启动无 ANR，退出记录为实验的 kill background。此项仅证明该正式包、API 31、单次真实周期的 Doze 延迟与恢复，不替代其余省电模式、真实账号、OEM 或长期观察。

## alpha17 后台受限诊断

报告新增 `backgroundRestricted`，读取 Android ActivityManager 的实际限制标记。独立 API 31 模拟器通过真实 app-op 切换允许→受限→允许，预览报告分别出现 false→true→false，额度请求数不变；[原始回归](diagnostics/alpha17-background-restriction-report-api31.txt) 为 14.644 秒成功。测试结束恢复原 app-op，未触碰同机另一个正式 alpha16 后台观察设备。该字段表示系统后台限制状态，不声称可读取小米自启动、任务锁定或其他 OEM 私有开关。

alpha17 本地验证：71 项 JVM 测试、lint 和构建通过；完整 AppFlow 五项通过（24.266 秒），见 [runner 原文](diagnostics/alpha17-appflow-api31.txt)，覆盖诊断反复预览／清除、真实后台限制切换、隐私、主题与后台引导。跨版本仍以对应提交的云端 CI 为准。

### 后台限制恢复命令修正

首轮外部后台受限观察满 16 分钟后，恢复脚本把 app-op 写为显式 `default`；API 31 的 JobScheduler 仍显示 `readyNotRestrictedInBg=false`，四分钟内无请求，探针正确失败。随后只将同一 app-op 改为 `allow`，未打开 App 或强制执行任务，原任务立即自行刷新一次。原失败、修正动作与快照保留在 [失败证据](diagnostics/power-api31/restricted-default-failure/summary.json) 和 [恢复日志](diagnostics/power-api31/restricted-default-failure/corrective-allow.txt)，不把人工修正后的结果改写成原探针成功。

[AOSP 官方测试说明](https://source.android.com/docs/core/power/app_mgmt#test-app-restrictions) 使用 `allow` 恢复默认允许行为。外部探针、原 `power_smoke.sh` 和诊断设备测试统一修正，拒绝把已有显式 default 当成未受限状态；恢复后检查实际状态。诊断回归增加最终 `isBackgroundRestricted=false` 断言，独立 API 31 实测 3.776 秒通过（[原文](diagnostics/alpha17-background-restoration-api31.txt)）。早期省电探针的缓存／组件结果不作为后台权限已经恢复的证明。

首次修正重跑在施加限制前因应用仍处于近期服务清理阶段而退出；保留 [前置失败](diagnostics/power-api31/restricted-default-failure/retry-process-still-alive.txt)。脚本改为最多等待 60 秒、重复普通 `am kill` 并确认 PID 消失，仍不使用 force-stop；设置完成前任务已到期则拒绝运行。新的完整周期正在观察，E03 仍待验收。

### API 31 外部后台受限实测

修正后的完整重跑通过（[汇总](diagnostics/power-api31/restricted/summary.json)、[原文](diagnostics/power-api31/restricted/observer.txt)）：约 974 秒限制期间零新增请求，默认网络持续 VALIDATED，成功缓存时间及组件 ID 不变。恢复 allow 后，原周期任务自动请求一次，period_count 从 3 增至 4，fetchedAt 从 1791629798 更新为 1791630946；组件显示更新于 19:15，crash buffer 为空、系统无 ANR。未打开 App 或强制执行 job。

[组件截图](diagnostics/power-api31/restricted/recovered-widget.png) 采集时下一轮省电观察已经开始，因此只作为恢复后组件时间的证据，系统限制状态以各阶段快照／JobScheduler 记录为准。首轮 default 恢复失败及第一次重跑的进程前置失败继续保留，不替换成成功结果。当时系统省电完整周期仍在运行；最终结论见下一节。

### API 31 外部系统省电实测与 E03 结论

系统省电完整周期通过（[汇总](diagnostics/power-api31/saver/summary.json)、[观察原文](diagnostics/power-api31/saver/observer.txt)）。系统确实显示 Battery Saver ON，默认网络保持 VALIDATED；约 973 秒期间零新增请求、成功缓存不变。关闭省电后，原周期任务自动请求一次，period_count 从 4 增至 5，fetchedAt 从 1791630946 更新为 1791631985；[组件截图](diagnostics/power-api31/saver/recovered-widget.png) 显示更新于 19:33，crash buffer 为空、系统无 ANR，限制均恢复。

Doze、后台受限、系统省电三种真实状态的单周期观察均通过，配合 API 29／31 的真实 503 指数退避、第三次终止及新请求恢复验证，E03 完成。实际正式包来源均为 alpha16／6ac605a；alpha17 仅新增诊断字段与测试，未修改这些后台生产路径。此结论不扩展为 OEM、真实账号或长期稳定性通过。

[清理记录](diagnostics/power-api31/cleanup.txt) 确认合成账号和 APK 已删除、hosts 恢复、临时 CA 和 adb reverse 移除、全局代理关闭；模拟器实际重启后再次核验，实验服务停止、临时私钥删除。用户的 APK 签名 keystore 未读取或修改。


## alpha20 应用更新提醒

2026-10-22 本地通过 109 项 JVM 回归、46 项发布工具回归、debug lint（0 错误、43 既有警告）、App 与 instrumentation APK 构建。新增客户端测试覆盖正式／预发布筛选、版本号顺序、签名不匹配、同版本／降级、无资产、非法 URL、损坏元数据、分页与预算、限流／失败、取消及请求不含账号凭据。仓库回归覆盖 12 小时节流、并发合并、关闭后手动检查、权限拒绝后补通知、跨进程去重、升级清理、断网缓存、时间回拨和取消恢复。发布工具核对 metadata 与签名来源一致，失败前不得写入 GitHub。

API 31 ARM64 本地模拟器执行 `AppUpdatesDeviceTest` 和 `AppFlowTest#manualAppUpdateCheckWorksWithAutomaticChecksOffAndShowsDownloadEntry`：3 项实际通过，Android 13+ 通知权限拒绝项按条件跳过。实际验证 WorkManager 唯一任务、关闭任务、通知显示与清除、自动检查关闭后的手动检查、设置及首页下载入口，且不增加额度请求；原生设置截图已查看。运行记录保存在本机 `artifacts/app-update-validation/`，模拟器验证后关闭。

发布后还需验收：

1. Android 13+ 首次通知授权、拒绝、再次从系统设置开启及渠道关闭；拒绝后仍能查看 App 内提示。权限拒绝设备测试在无授权环境运行，通知投递测试在已授权环境或 API 29/31 运行，避免测试内撤销权限杀死自身进程。
2. 安装固定签名的 alpha20，再发布更高 versionCode 候选：仅提醒一次，点击进入准确发布页，手动覆盖安装后旧通知／缓存清理。debug APK 不作为固定签名升级的验收依据。
3. 网络断开／恢复、省电和 12 小时后台观察。WorkManager 的周期不是准点承诺，见 [官方调度说明](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)。
4. 旧版 alpha19 手动升级到支持检查的版本；同 code 的构建重跑不产生新提醒。

## alpha21 Token 用量趋势

2026-10-22 本地 `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` 成功：152 项 JVM 测试、0 失败，lint 无错误（43 项既有警告），App 与设备测试 APK 已生成；46 项 Python 工具回归通过。相对 alpha20 增加 43 项回归：9 项严格解析、8 项 HTTP／诊断、18 项仓库并发／缓存／取消／账号隔离、7 项趋势聚合和 1 项小时观察统计隔离。数据均为合成 fixture，未读取真实账号凭据或响应。

API 31 ARM64 模拟器中，`TokenUsageDeviceTest` 最终 3 项通过（7.120 秒）：

- 切换 7／30 天，保留服务端日期；真实零值、缺失日期和区间合计分别展示，30 日明细可滚动至最早日期。
- 手动统计请求返回 403 时保留上次曲线及额度状态，不增加额度请求；退出后清空统计，连接另一个合成账号不显示旧值。
- 深色示例可切换周期，不请求账号统计，也不把示例写入账号快照。

同轮 `AppFlowTest` 6 项、`AppUpdatesDeviceTest` 的通知投递／清除和唯一任务／关闭 2 项通过；API 33+ 通知权限拒绝项在 API 31 条件跳过。已查看浅色 7 日图、深色 30 日图、逐日明细和缓存错误状态四张原生截图。证据保存在本机忽略目录 `artifacts/token-trend-validation/`，包括 `gradle-final.log`、`device-build-final.log`、`python-tests.log`、`device-tests.txt`、`token-device-tests-final.txt` 与 `screenshots/`。

首轮失败记录保留：取消后的立即重试已发起第二次 HTTP 调用，但 `localhost` 被选为未监听的 IPv6 地址，产生 `ConnectException: Connection refused`；测试服务现明确绑定并使用 `127.0.0.1`，保留取消完成屏障、双请求和缓存更新断言，18 项仓库专项及全套 JVM 回归通过。首轮设备测试的日期选择器同时匹配到弹窗内外的日期，现限定到明细列表并断言可见，三项 token 测试全部重跑通过。两处均为测试修正，未放宽产品错误处理或开启 HTTP 自动重试。

真实账号的统计可用性、订阅／工作区覆盖、服务端日日期的时区定义及统计延迟仍待验证。本地合成数据回归不替代真实接口验收或远端多版本 CI；输入／输出／缓存 token 与费用拆分不在当前接口范围内。

## alpha21 显示方式同步小组件

2026-10-22 修复全局“显示剩余额度”被已有小组件实例覆盖值遮蔽的问题。全局值与实例覆盖的清理在同一次 DataStore 编辑中完成；原有组件更新和 Flow 订阅负责渲染，两种组件的主题保留。独立配置仍可使用，下次全局切换再次统一。

本地 152 项 JVM 回归、debug lint（0 错误、43 项既有警告）、App 与测试 APK 构建通过。API 31 的 `StorageDeviceTest` 两项偏好测试通过，覆盖独立配置、相反实例值的全局同步、主题保留、未配置实例继承、重新读取和再次统一。新增 `WidgetDisplayPreferenceTest` 实际点击 App 开关，在两个标准组件和一个紧凑组件中验证剩余 62%→已用 38%→剩余 62%，检查标签、主题与请求计数；不由测试额外调用刷新来完成切换。最终专项通过（5.465 秒），已查看 `used.png`／`remaining.png`，三组件的数字、进度条与口径一致。

证据位于本机忽略目录 `artifacts/widget-display-validation/`：`gradle.log`、`device-build-capture.log`、`device-tests.txt`（两项偏好通过与首轮宿主失败）、`widget-test-capture.txt` 和两张最终截图。测试宿主与 App 使用独立任务，避免 ActivityScenario 清理 App 任务时同时销毁模拟桌面；返回宿主并确认窗口焦点，等待绘制帧后截图。早期空白／旧帧截图不作为视觉通过证据。本次未运行远端 CI 或 OEM Launcher 验收。
