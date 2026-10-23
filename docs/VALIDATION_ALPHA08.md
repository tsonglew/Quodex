# alpha08 刷新状态与平台验证

2026-10-10，versionCode 8／0.1.0-alpha08。

- 已初始化的共享快照读取不再等待网络请求占用的互斥锁；手动刷新请求仍在执行时先唤醒 Glance，结束后再次更新。保留 8 秒超时续办与服务端限流约束。
- 本地 47 项 JVM 测试、lint、App／测试 APK 构建通过。新增慢请求期间快照读取与进度更新顺序回归。
- Android 35 首轮 13 项设备测试中 12 项通过；一项组件布局测试捕获到 RemoteViews 替换期间的空 layout。改为在同一 UI 回调确认布局并检查、截图后，3 项 WidgetRenderTest 全部通过（73.853 秒）。未声称完整 13 项单轮全部通过。
- 标准 AOSP 35 实际 Launcher：标准组件确认取消及再次添加、2×1 添加确认、两种桌面组件显示、点击标准组件打开 App 均通过；系统选择器显示两个入口，拖入 2×1 进入独立配置页并验证取消。该 Launcher 标准组件显示为 2×3；不据此认定其他 Launcher 或小米验收通过。
- actionlint、ShellCheck 与 11 项交付脚本测试通过。新增 API 37.0/google_apis 和 37.2/google_apis_ps16k、4 GB RAM；签名及演练明确进行 16 KB zipalign 并检查。新增矩阵尚待 CI，原 APK 未发现对齐失败。
- debug APK SHA-256：`108cc019b6bbabf98ef65840852fdf1235db68347cf4e3b798fdc1bd63491b43`。

真实小米 Android 17 手动刷新、清理后台后的更新、长期观察、正式签名发布及实际 GitHub deployment 仍待验证。GitHub PR 写入权限问题尚未解决。

Android 17 本地镜像首次启动完成，系统报告 Android 17／API 37／4 KB 页面，App 与测试 APK 安装成功。首轮旧 Espresso 在页面测试抛出 `InputManager.getInstance` 不存在；存储两项、唯一周期任务与刷新回调通过。测试依赖更新到 AndroidX Test runner 1.7.0／JUnit 扩展 1.3.0／Espresso 3.7.0，升级后页面测试已越过系统方法异常；中断前一轮留下的示例状态又暴露页面测试依赖执行顺序，已增加每项开始前清理示例／连接状态。升级后的完整 13 项运行有 11 项通过、2 项因残留示例状态失败；加入独立清理后针对性重跑 AppFlowTest，3 项全部通过（19.396 秒）。Android 17 共 13 个独立测试已有通过覆盖，未声称单轮完整 13 项全部成功。该修复依据 [官方发布说明](https://developer.android.com/jetpack/androidx/releases/test)。

## 后续生命周期回归

Android 17 上新增真实 AppWidgetHost 回归通过（9.883 秒）：280×150 → 140×230 → 140×150，检查实际 RemoteViews 从两窗口到单窗口布局；关闭宿主删除实例后，等待平台删除广播清除已用／深色配置；重新分配实例得到不同 ID 和默认剩余／系统主题。debug 与测试 APK 重建、lint 通过。该回归为第 14 个设备测试，独立执行通过，未据此声称完整 14 项单轮通过。

本地加入 debug 宿主缩放方法后的 APK SHA-256 为 `fd5e9c30529a4523bfeca6cf458370cc9ff97df779bd51de04ab52c80f2835cf`；上文哈希对应最初 3f0d798 构建。生产源码未变更。

## 已连接状态与完整重跑

测试 runner 注入仅测试包使用的 Application／OkHttp 传输：额度请求返回合成数据，其他请求以网络失败结束，不向真实服务发送测试凭据。生产 Application 仅开放 API 属性覆盖，正常运行继续使用原有客户端。

真实 RemoteViews 点击绑定的刷新 PendingIntent：一个标准＋两个紧凑实例，连续点击两次，合成额度从剩余 62% 更新到 83%；三个实例均观察到刷新中符号与新值，计数仅增加一次，真实 WorkManager 数据库仍只有一个活跃周期任务。最初测试直接对 TextView performClick 无监听，修正为点击 Glance 的实际包装层；双实例版两项通过后扩为三个实例。完整重跑首次因测试传输对授权请求抛出非 IOException 崩溃，改为正常离线失败后，15 项设备测试单轮全部通过（123.716 秒）。

47 项 JVM、lint、App／测试 APK 构建通过；新 APK SHA-256：`7b9598b03f9c4ddb907b4817f0ff038c1b9eaed11d5d01fc73f31e43256d698f`。D04／D06 完成，实际小米、OEM／进程恢复和长期观察不据此完成。

## 云端启动失败与修复

[37986219241](https://github.com/tsonglew/Quodex/actions/runs/37986219241) 的交付、构建及 API 29／31／35／36 设备 job 成功。API 37.0 在 boot_completed=1 后 input/settings 服务不可用；37.2 发送按键时 Broken pipe，均未开始应用测试，统一 gate 失败。保留失败证据。

新增 Android 17 启动脚本：相同官方镜像与 4 GB RAM，等待核心服务注册、包查询、settings 与无操作按键连续三轮成功后执行完整原测试，保存启动及 logcat 日志。14 项交付脚本测试（含这两种实际启动失败回归）、actionlint、ShellCheck 通过；就绪探测在本地 Android 17 成功。云端新启动流程及 16 KB 仍待运行。所有普通 CI job 改为检出事件 SHA，避免分支移动使不同 job 测试不同提交。

## Pixel Launcher 系统选择器

Android 17／API 37 Google APIs 模拟器、Pixel 6 配置、Pixel Launcher（com.google.android.apps.nexuslauncher）：搜索 QuotaPal，标准入口标为 2×2，紧凑入口标为 2×1；两者通过 Add → 独立配置 → 保存，真实桌面均出现。紧凑实例向右扩大为四列，额度与绝对更新时间完整；长按 Remove 删除，显示 Item removed；再次添加取消后桌面无实例；再次添加选择深色保存后为深色，与标准系统浅色实例并存。dumpsys 确认真实 Launcher 绑定两个 provider。原生截图已目视检查，保存在本机 .tools/alpha08-pixel17（picker-added、resized、readded-dark、both-providers）。

结合此前 AOSP 35 的 App 添加入口、取消和桌面打开，以及真实宿主缩放／删除／配置清理回归，D01／D03 完成。本轮使用示例数据；不代表小米或 Samsung、真实账号后台稳定性通过。

## 大字体待修复

Pixel Launcher 两种原生实例在 130% 字体下，额度与时间节点均可见；200% 时目视发现标准 2×2 底部成功时间被裁掉，紧凑 2×1 时间及刷新符号出现省略。原生截图 font-1.3／font-2.0 保存在上述目录；已恢复模拟器 font_scale=1.0。这是实际布局缺陷，D09 保留未完成，需要大字体下优先保留额度、完整时间与刷新入口并增加回归。

对模拟器进程使用 SIGKILL 确认原 PID 消失，重开后示例模式恢复；示例本身重新生成时间，不是持久真实成功快照，不能据此完成 D08。
