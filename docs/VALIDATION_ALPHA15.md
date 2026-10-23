# alpha15 诊断与签名验证

日期：2026-10-10（Asia/Shanghai）。版本 0.1.0-alpha15／15。基于主分支 70c4580，开发分支 codex/diagnostics-observation；本文的本地结果针对当前工作区，精确提交的 CI 结果另行记录。

## 实现

- 本机私有 noBackup 目录保留最近 200 条诊断事件及 168 个 UTC 小时统计桶；记录请求类别、HTTP 结果、网络失败、真实快照写入、成功间隔、刷新跳过原因、后台任务、页面／组件状态。
- 设置提供预览、系统分享和清除。报告加入版本、网络、省电、后台任务状态；任务查询各最多等 2 秒。
- Java／Kotlin 未捕获异常只记录大类及是否主线程，继续系统原异常处理；无异常消息、堆栈、URL、凭据、账号标识或额度数值，无自动上传。
- 用户确定 GitHub Releases APK 渠道，创建固定 keystore 并配置 android-release。只读 API 已确认所需四项 secret 名称齐全，证书变量格式正确；未读取 secret 内容，实际匹配仍由签名执行证明。
- 手动 Android CI 新增 check-signing，仅主分支完整 CI 成功后使用发布环境，沿用证书指纹／版本／对齐校验并上传签名验证产物，不创建或公开 Release。产物 provenance mode=signing-check，与正式发布区分。

## 本地结果

本机补齐并校验官方 Android 36 SDK、Build Tools 35.0.0、platform-tools；工程依赖已下载。先前缺 SDK／离线缓存的限制已解决。

- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`：通过，BUILD SUCCESSFUL，5 分钟；JUnit XML 共 71 项，0 failures、0 errors。
- debug lint：0 错误、18 个 warning，完整报告在 app/build/reports/lint-results-debug.html。
- 诊断独立 Kotlin／JUnit：10 项通过，涵盖容量轮转、跨实例保留、存储失败、7 天裁剪、时间回拨、清除、异常脱敏与系统异常处理保留。
- 交付脚本：22 项通过，新增签名验证模式不调用 GitHub、验证模式仍拒绝指纹不符。
- actionlint 1.7.12：通过（本机未安装 ShellCheck，使用 -shellcheck=''；CI 仍运行完整 ShellCheck）。`bash -n scripts/publish_release.sh`、`git diff --check` 通过。
- release lint：通过，0 错误。首次 R8 构建在 300 秒命令预算内未结束；更长预算继续运行后 `assembleRelease` BUILD SUCCESSFUL（3m27s）。aapt2 检查 minified APK 的包名 com.tsonglew.quotapal、versionCode=15、versionName=0.1.0-alpha15、minSdk=29、targetSdk=36；无 debuggable 标记或 debug 测试宿主。
- 本机 adb 设备列表为空。新增诊断预览／清除设备测试与现有组件测试的事件时序输出；设备执行结果待六版本 CI。

## 当前 CI 基线问题

只读核对确认 PR #9 已合并。主分支 [38013679974](https://github.com/tsonglew/Quodex/actions/runs/38013679974) 的 API 35／37.0 三组件刷新进度断言失败，统一 gate 失败，预览部署跳过。不能用此前某提交全绿覆盖此事实。

当前分支保留原请求去重、实际触摸、三组件进度与成功快照断言，加入诊断时序输出，等待新 CI 实际运行定位。没有以跳过／放宽断言取得通过。G02／G03／G08 继续待验收。

## 保留的真实验收

E03 的系统后台约束、E07／H04／I03 的 Samsung／小米 Launcher、E08 的 48 小时观察、E09 的七天自用及真实授权／续期保持未完成。签名环境配置不是实际签名、覆盖升级或公开发布成功的证据。


PR [#10](https://github.com/tsonglew/Quodex/pull/10) 已创建并关联本任务。诊断实现提交为 2699a6582429801ee836bc0c410924b6e3f6fa28；该提交 build／delivery 通过，设备矩阵随后被新提交取消，不能视为通过。重复 push 运行被取消后，旧版 always() gate 将取消显示为失败。

修复提交 3e5aeb5953cd8539d9252a1c18c17fd01d883872 取消开发分支重复 push 触发，并令整个运行被取消时 gate 跳过；实际构建或测试失败仍使 gate 失败。actionlint 与 diff 检查通过。[新 CI 38018860257](https://github.com/tsonglew/Quodex/actions/runs/38018860257) 已启动，完整结果待核验。

已实际下载运行 38018860257 的 android-apk，使用 apk_artifact.py verify 校验成功：source_sha=3e5aeb5953cd8539d9252a1c18c17fd01d883872，build_sha=7789849b5456b280254a6d1e1953f937afefde6b，APK SHA-256=aebd3830dbf6cac157843d520d8bbe6dd6d96235ddf5292a12868507e3625d1c。aapt2 独立核对包名、versionCode=15、versionName=0.1.0-alpha15、minSdk=29、targetSdk=36；apksigner verify 通过。此为 debug 预览构建，不证明固定正式证书或成功 deployment。

## PR 完整 CI 与预览验收

运行 38018860257 最终 success：delivery、build、API 29／31／35／36／37.0／37.2 六组设备测试、Android CI gate 全部通过。API 29 实际下载 HTML 报告中 diagnosticsCanPreviewAndClearWithoutUploading=passed（5.457s），realRemoteViewsClickFetchesOnceForThreeWidgets=passed（5.043s）；保留原三组件进度与请求去重断言。此前主分支失败记录仍保留，本轮成功不表示已证明所有间歇性失败原因。

预览工作流 [38019563477](https://github.com/tsonglew/Quodex/actions/runs/38019563477) success，deployment 6975361817 环境 android-preview/pr-10，sha/ref 均为 3e5aeb5953cd8539d9252a1c18c17fd01d883872，状态 success。[APK 下载入口](https://github.com/tsonglew/Quodex/actions/runs/38018860257/artifacts/11657277423) 对应已实际下载校验的产物。G03 完成；主分支完整回归、固定签名、覆盖升级和正式 Release 仍未完成。

## 合并后主分支回归与触摸坐标修正

PR #10 已在精确 head 46a7b402f280820f74c2d7c145740b18bf51e7f2 的完整 CI 38019644916 成功后合并，master=49093aa7fbb329138b89f9553dd4cfc88377e6a1。按用户授权触发固定签名验证运行 38020312346；它使用完整独立 gate，未公开发布 Release。

主分支运行 38020309282 的 API 31／37.0 三组件进度断言再次失败，其余四组成功，统一 gate 失败，G02 仍未完成。API 31 下载的完整报告中，种子快照成功后至断言失败均没有 WIDGET_CLICK／REFRESH_START／HTTP_START，三组件保持 62%。这是触摸未触发回调的证据，不能归因于请求或进度渲染慢。

测试此前把 getGlobalVisibleRect 的根 View 坐标直接用于屏幕触摸注入；Android 官方 View 文档明确区分根 View 与屏幕坐标。修正使用 getLocalVisibleRect 加 getLocationOnScreen，等待窗口焦点与布局完成，失败报告附触摸屏幕坐标与视图树。仍然注入两次真实触摸，保持 6 秒内三个进度状态、一次请求、最终 83% 和唯一周期任务的原断言。此修正与无回调现象一致，但云端完整矩阵通过之前不宣称间歇失败已解决。

坐标依据：[Android View API](https://developer.android.com/reference/android/view/View)。

## 坐标修正后的重复失败

PR #11 的精确提交 34704b3 六组矩阵与 gate 成功，API 31 原三组件测试通过（3.734s）；经用户确认合并后 master=d6f7cfda2e9525699167e9feba304db10dabfa6d。主分支运行 38030059760 的 API 31／35 再次失败，因此不能把 PR 首次全绿当成间歇问题已解决。

API 31 完整失败报告显示 screenBounds=Rect(823,1196–886,1245)，语义 TextView 的 hasOnClickListeners=false，直接父 FrameLayout 的 hasOnClickListeners=true。种子快照成功之后仍没有 WIDGET_CLICK／REFRESH_START／HTTP_START。后续候选改为定位这个直接动作容器的屏幕可见区域，等待 UI idle 后注入两次 50ms 按压、间隔 50ms 的真实触摸；debug 宿主记录最多 20 条输入事件及是否被处理，以区分输入未抵达与回调未执行。仍保留原 6 秒三组件进度、一次请求、83% 成功快照及唯一周期任务断言。候选修复需完整云端矩阵和主分支回归证明，不能仅靠坐标推断宣布解决。

本次同时在完整设备套件成功后追加五轮同一真实三组件点击测试，每轮仍执行原全部断言，失败使该设备 job 和统一 gate 失败；普通及 Android 17 均经过共同的 device_ci.sh，因此六组均覆盖。每轮 instrumentation 原始日志随截图产物保留。用重复执行检查已观察到的间歇性，不把一次全绿当作稳定性证明。

PR #12 首轮 38030802797 的 API 35／37.0 失败，其他四组成功。API 35 在目标就绪阶段报 `Refresh action must become visible`，尚未注入触摸，不能作为触摸已送达的证据。后续候选沿语义节点向上查找最近动作容器，拒绝包含额度正文的外层打开 App 容器，并用窗口焦点、实际可见矩形判断可点击位置；不再把 `isLayoutRequested` 当作可见性前提。失败输出增加父节点、焦点、尺寸、布局请求状态及截图。此候选和五轮重复检查仍待新的云端执行验证。

API 37.0 的输入轨迹显示第一次 DOWN 被处理而 UP 未被处理，随后真实触摸触发 MainActivity 的全组件打开动作。logcat 在 06:32:10.777 通知主组件更新，06:32:10.793 启动 MainActivity；这支持 RemoteViews 替换与手势竞争的判断。debug 宿主记录实际 updateAppWidget 时间，测试在 UI idle 之后重新读取位置，并等待目标对象、矩形以及宿主更新均稳定 500ms 才注入触摸。原刷新行为断言和六组五轮重复检查保留；该候选仍需 CI 验证。

## master 自动签名产物与点击修复结果

PR #13 的精确提交 503d59d 在运行 [38031371128](https://github.com/tsonglew/Quodex/actions/runs/38031371128) 完整六组 CI 成功后合并，master=a983dfd9198356340795bbdc2e30b6aa9ecca3ed。合并事件自动触发 [38031990526](https://github.com/tsonglew/Quodex/actions/runs/38031990526)，debug／release 构建、lint、JVM、六组设备测试与 minified 页面检查、统一 gate 和 Build fixed-signature APK 均成功，无手动触发、无公开 Release。

已实际下载 [android-signed-apk](https://github.com/tsonglew/Quodex/actions/runs/38031990526/artifacts/11662492337)。独立校验结果如下。

- APK：QuotaPal-0.1.0-alpha15.apk，包名 com.tsonglew.quotapal，versionCode=15，minSdk=29，targetSdk=36。
- SHA-256：85bbcc69e94f39a7f5334f4ecdb0e74c142e16d312c7befa316b08e99c9aeb26；与 SHA256SUMS 和 provenance.json 一致。
- apksigner 验证通过，实际证书 SHA-256 为 5e7980c122100ccf9799a5174cb0e5a1748b8c442c09c015e145003145d2b5d4，与 android-release 的公开证书变量及 provenance 一致；未读取或导出 keystore／密码。
- zipalign -c -P 16 4 通过。provenance 的 source_sha 为上述 master a983dfd，mode=signing-check；此模式只生成签名资产，不调用 Release 发布接口。

PR #12 的精确提交 1b9bcd363d0cac44ca8a35514c83bcc4de096ae9 在 [38031921806](https://github.com/tsonglew/Quodex/actions/runs/38031921806) 完整六组 CI 成功；每组在原套件后又通过五轮真实三组件点击检查，合计 30 轮追加。已下载 API 37.0 原始日志，attempt-1 至 attempt-5 均为 OK (1 test)／INSTRUMENTATION_CODE: -1。原进度、请求去重、成功快照与唯一周期任务断言保留。该修复合并为 master 12c557bff84fd78230a586362268c3cf43d87794，其自动运行 38032639377 仍单独等待结果；不把 PR 成功当成新 master 已完成验证。

G02 的 PR／master gate 有实际成功证据。固定签名配置已证实可用，但正式签名覆盖升级／数据迁移（F02）、版本 tag 的公开 Release（G05／G08）、真实账号／OEM／48 小时和七天观察仍未完成。
