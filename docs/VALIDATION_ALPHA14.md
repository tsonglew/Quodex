# alpha14 验证记录

2026-10-10，versionCode 14。本轮补刷新仓库的存储异常边界，并修复固定 Android 17 模拟器的 Linux 运行依赖。

## 刷新存储边界

三个新 JVM 回归先在原实现明确失败：刷新前设置读取 IOException、凭据读取 IOException、处理 503 时记录失败的设置写入 IOException，均逃逸到调用方。修复后保留原成功快照与凭据，以 STORAGE 状态和可重试结果返回；协程取消继续传播，取消后的回滚写入失败不替换原取消异常。

如果 Retry-After 写入失败，同进程仍需遵守截止时间：增加受 Mutex 保护、绑定账号 generation 的内存 guard，与磁盘截止时间取更严格值。新增回归确认清除短尝试保护后仍不发送额外 HTTP，另一个账号 generation 不受旧 guard 影响。未能写盘的 guard 无法保证跨进程持久化；这不是整个 App 的所有存储故障路径验收。

56 项 JVM 测试零失败／零错误，lint、应用及测试 APK 构建通过。API 37 ARM64／emulator 37.1.11 的页面、手动刷新、Worker 七项定向回归首轮六项通过，一项旧夹具在人工清空磁盘 Retry-After 后被内存 guard 拦住，整轮明确失败（27.754 秒）。夹具改短 Retry-After 并等待实际截止时间，不删除限流期间请求数、缓存保留和恢复断言，最终原七项回归全部通过（33.328 秒，7 个成功状态及正常 instrumentation 完成码）。

## Android 17 CI 依赖

固定 37.1.11 的 CI 38001424560 两个 Android 17 job 在运行时加载前失败：缺少 libpulse.so.0，未执行任何 App 测试。下载／SHA-256 校验已通过；添加仅 Android 17 job 的 libpulse0 安装步骤。此日志不能证明原 SurfaceFlinger 问题已经解决，仍需新云端完整套件及 16KB 镜像结果。

## 本地开发包交付


apksigner 实际验证 v2 签名成功；本地 Android Debug 证书 SHA-256 为 `a83328765a27dbb4713f363f5869edbfea78603cf1dc78563d41e6dcafe63290`。这是供自用验收的开发产物，不是固定发布签名或已通过完整云端 gate 的 Release，也不计为 G08 deployment 验收。

## 云端终态与有效图形配置对照

CI 38002272484 全部终态：API 29／31／35／36 成功，37.0／37.2 在补齐 libpulse0 并实际启动 37.1.11 后仍复现相同 SurfaceFlinger／ReadColorBufferDma 断言，安装时系统服务不可用；gate 失败。降级未解决云端问题，已从脚本撤回固定运行环境，保留完整镜像与测试范围。

本地 37.2.12 的 verbose 日志确认 GLDirectMem 开关实际传入了 Gfxstream（GlDirectMem=0），此前“未生效”的解释不成立。继续同时关闭 GLDirectMem／GLDMA／HasSharedSlotsHostMemoryAllocator，三项实际渲染器值均为 0，仍在 App 测试前复现原断言，因此未将该无效配置加入 CI。

依据官方 emulator 发布说明测试 VulkanNativeSwapchain composition，日志确认 VulkanNativeSwapchain=1、GuestVulkanOnly=1，自动启用 GuestAngle；通过框架稳定准备，启动 crash buffer 为空。完整原生普通套件 18 项实际测试全部通过（129.937 秒，另 4 项条件探针跳过），包含真实小组件点击及渲染断言。CI 候选使用相同两个 feature 参数并保留 verbose 实际值日志；本地 ARM64 通过不证明云端 x86_64／16KB，不据此勾选 E06。

- Vulkan composition 补充回归：外部强停 seed／restore 均通过，真实时区／时钟回退／DST 探针通过（9.6 秒），结束 crash buffer 为空。

- CI 38004158377 终态：29／31／36 成功；35 真实刷新测试查找 TextView 上的描述失败，改为等待完整视图树中唯一刷新语义与实际点击入口，本地 API 35 两项刷新测试（9.088 秒）及完整 18 项普通套件（123.699 秒）通过，保留三组件／单请求／刷新中断言。37 两组在渲染器初始化前失败，实际日志 API level: 3、Vulkan=0、VulkanNativeSwapchain=1；本地同路径 API level: 37、Vulkan=1。下一候选显式开启 Vulkan 以消除该配置冲突，仍须云端完整验证，不认定已修复。X11 备用库名加载成功，不归因缺库。

- CI 38005242748 全部设备终态：29／31／36 成功；35 真实点击已通过入口查找，但固定 2 秒响应窗口未观察到三组件刷新中。测试改为可控 HTTP 响应闸门，6 秒内验证三个组件的刷新中与唯一待处理请求后才释放响应（闸门最多 7 秒，保留生产 8 秒立即请求预算），finally 释放；本地 API 35 完整 18 项普通套件通过（113.127 秒）。37 两组 Vulkan=1 已确认、compositor 初始化已越过，但 SurfaceFlinger 在 libGLESv2_angle 的 FindAndAllocateCompatibleMemory／AllocateBufferMemory 路径反复 SIGABRT，系统准备超时；没有进入 App 测试，不计 E06。

- Android 17 新对照只将 AVD 根 target 明确为 android-37，保留原 37.0 ARM64 系统镜像并恢复默认 software 渲染（无 Vulkan composition／GuestUsesAngle 强开）。实际 API level=37、Vulkan=1、GlDirectMem=1、HasSharedSlotsHostMemoryAllocator=1；框架准备通过，guest SDK=37、PAGE_SIZE=4096，完整 18 项普通套件通过（170.281 秒），包含最新可控响应的真实三组件点击刷新测试。准备以相同元数据修正进入云端 x86_64／16KB；新增 guest SDK／页面大小实际检查，E06 仍未完成。

- CI 38006708030 设备全部终态：31／35／36／37.2 成功，37.2 实际 guest SDK=37、PAGE_SIZE=16384，完整 18 项普通测试与四个独立探针通过；37.0 实际 SDK=37、PAGE_SIZE=4096，17 项普通测试通过，唯一失败为三组件刷新中观察。两组实际 Vulkan／GlDirectMem／HasSharedSlotsHostMemoryAllocator 均为 1，AVD 主版本元数据修正已使系统进入 App 测试；统一 gate 因 29／37.0 测试失败继续不通过，E06 不勾选。

- API 29 缩放失败核对平台源码：HostView 两种 updateAppWidgetSize API 会扣除默认 padding，测试宿主却传入内容尺寸。修正所有 API 的宿主尺寸换算，新增 provider 实收尺寸断言及实际文本／options 失败信息；API 35 首轮新断言明确捕获 140 被扣成 124，修正后通过。刷新测试改为向唯一可见刷新语义区域实际注入两次触摸，避免沿父级 performClick 误调用打开 App 动作，保留刷新中、三组件 83%、单请求及唯一周期任务断言。两处变更的三项针对性设备回归：API 35 全部通过（9.28 秒），API 37 全部通过（25.935 秒）；API 29 与云端仍待新候选验证。

## 六版本 CI 验收

2026-10-10，源提交 f0e1fffc58452bd62545e623d08ca329c3c1b2dd 的 [运行 38007906590](https://github.com/tsonglew/Quodex/actions/runs/38007906590) 全部终态成功：build、delivery、29／31／35／36／37.0／37.2 六组设备矩阵、Android CI gate。37.0／37.2 各 18 项实际普通测试及四个独立探针通过，实际 guest SDK=37、PAGE_SIZE=4096／16384；三组件真实触摸刷新及缩放尺寸断言保留。E06 已勾选完成。

里程碑 PR 创建再次调用 GitHub 集成，实际返回 403 Resource not accessible by integration，尚无新 PR 或合并；没有绕过权限直接推送 master。G02 的 PR／主分支、G03 的实际源提交 deployment、G05／G08 的正式固定签名和发布仍未验收；真实账号完整生命周期、OEM、48 小时／7 天观察仍保留。
