# 实际网络约束验证

2026-10-10，应用 alpha12，API 29 自建 AOSP 模拟器。

`NetworkConstraintDeviceTest` 调用生产 `SyncScheduler.refresh`，使用真实 WorkManager 和实际 Android 网络状态。测试 runner 截获全部用量请求，合成凭据不会发到外部。

第一轮真实断网阶段通过：Wi-Fi／移动数据均关闭，OS activeNetwork=null，任务保持 ENQUEUED／0 次执行，零请求且缓存保留。恢复后模拟器只有 PARTIAL_CONNECTIVITY，未获得 VALIDATED，任务正确继续等待，整项测试失败。没有放宽网络约束或把这轮记为通过。

宿主使用本地 HTTP 代理；为测试模拟器临时配置代理并重新联网后，OS 确认 VALIDATED。第二轮探针通过（5.896 秒），增加初始和恢复后的 VALIDATED 明确断言后，通过独立 `scripts/network_smoke.sh` 再跑（5.465 秒）：

- 真实断网 3 秒内任务等待，runAttemptCount=0、零请求、原成功快照不变。
- 恢复原 Wi-Fi／移动数据设置，OS 重新验证互联网。
- 实际调度的 Worker 成功结束，只请求一次，已用从 38% 更新为 17%，失败状态清除。

finally 取消测试任务、清除合成账号并恢复两项网络开关；外部检查均为原值 1。额外测试代理的三个原设置均为 null，已清除并恢复原状。实验室代理配置不是 App 产品功能或 OEM 兼容结论。

超时、429／Retry-After 与 503 的处理另由已有 `SyncWorkerDeviceTest` 两项回归覆盖，见 [alpha10](VALIDATION_ALPHA10.md)。这些是合成服务故障，没有声称对真实 Codex 服务注入了故障。E02 的网络与故障处理验证完成，跨版本和 OEM 仍由 E06／E07 单独验收。

测试 APK 构建／lint、20 项交付脚本测试、ShellCheck 与 actionlint 通过。普通套件默认跳过需要明确启用的网络探针，CI 在独立生命周期及系统时间探针后单独运行。

## Android 17 CI 后续诊断

CI 37997152626 的 37.2 crash buffer 明确记录 SurfaceFlinger 的 RegionSampling 线程因 `!rcEnc->featureInfo()->hasReadColorBufferDma` 断言 SIGABRT，发生在安装之前并反复出现。adb 为 37.0.1，说明仅更新 platform-tools 并未解决问题；同时有 UWB 无串口崩溃，但尚无证据表明它是安装失败的直接原因。

下一轮将 Android 17 专用启动参数从已弃用的 swiftshader_indirect 改为 software。依据 [Android 官方图形加速说明](https://developer.android.com/studio/run/emulator-acceleration)，software 会选择适用的软件后端。这是待云端验证的配置修订，不宣称已解决系统镜像缺陷；完整 UI／组件渲染测试和截图继续执行。

后续结论（2026-10-10）：上述图形配置对照已由 [运行 38007906590](https://github.com/tsonglew/Quodex/actions/runs/38007906590) 的六版本完整 CI 验证。最终修正为 AVD 根 target 明确主版本 android-37，保留默认 software 渲染与原 37.0／37.2 镜像；两组 actual SDK=37、页面大小 4096／16384，普通测试及独立探针均通过。历史失败不能继续当作当前支持结论，详见 [alpha14 验证](VALIDATION_ALPHA14.md)。
