# 隐私说明与数据流核对

更新：2026-10-30。完整政策采用随 App 打包的隐私说明：[简体中文](../app/src/main/res/raw-zh/privacy_notice.txt)、[English](../app/src/main/res/raw/privacy_notice.txt)、[日本語](../app/src/main/res/raw-ja/privacy_notice.txt)，设置和连接对话框读取同一份文件，离线可查看。此页为实现核对，不是另一份政策。

| 数据／行为 | 实现证据 |
| --- | --- |
| 应用语言 | `i18n/AppLanguages.kt`：Android 13+ 使用系统应用语言，旧版本由 AndroidX 在本机保存选择；语言偏好不随额度或更新请求发送。 |
| 版本检查与通知 | `update/GitHubUpdateClient.kt`：独立客户端访问 api.github.com 的公开 Releases，不携带账号、令牌或设备标识；签名比较在本机完成。默认开启目标 12 小时检查，设置可关闭；`AndroidUpdates.kt` 使用 WorkManager 和本机通知，点击打开 App；用户主动下载后由 `ApkDownloader.kt` 通过独立公共客户端获取 APK，校验哈希、包名、版本和签名，`InAppUpdate.kt` 通过仅暴露 updates 缓存目录的 FileProvider 调起系统安装确认。安装包不含账号数据，取消／失败会删除未完成文件；无静默安装。`UpdateRepository.kt` 与独立 DataStore 保存公开版本缓存、时间和提醒去重；退出账号保留这些偏好。 |
| 授权、续期、额度请求域名及携带信息 | `CodexApi.kt`：authBase、usageUrl、设备码／令牌请求、Authorization／ChatGPT-Account-Id headers；没有聊天生成调用 |
| Token 统计 | `CodexApi.tokenUsage()`：仅向已有 chatgpt.com 服务发送账号请求；`TokenUsageParser` 忽略 profile 身份资料，只保留统计及时间，将服务端错误转成布尔标记。缓存跟随账号快照，退出／切换时隔离，诊断不包含 token 用量数值。 |
| 凭据加密与存放 | `Storage.kt`：CredentialVault、noBackupFilesDir、AndroidKeyStore、AES/GCM/NoPadding、原子文件 |
| 快照及偏好 | `Storage.kt`：Room 单槽 usage_snapshot 保存额度和可选 token 日桶／汇总／获取时间，DataStore 显示偏好、组件配置、尝试与错误类别；快照并非加密数据库 |
| 桌面展示 | `QuotaWidget.kt`：额度、状态、成功时间传给 Launcher；`TokenHeatmapWidget.kt` 还提供每日 token 热力图、合计、记录日期及统计状态，存在该组件时后台任务会获取 token 数据并遵守独立缓存和限流。`DesktopQuotaIcon.kt` 仅在用户确认添加后更新固定快捷方式的百分比图像、周期／状态说明及数值元数据。系统桌面持有图标缓存；关闭或退出会请求恢复普通 logo／未连接。不传账号标识或令牌。 |
| 退出 | `UsageRepository.logout()`、`MainViewModel.logout()`：删除登录文件及快照、清理同步状态、取消任务、更新组件；保留显示偏好，不调用远程撤销 |
| 备份 | 主 manifest 与备份 XML：allowBackup=false，关闭云备份／迁移；登录文件另放 noBackup 目录 |
| 诊断与分享 | `Diagnostics.kt`：私有 noBackup 目录最多 200 条本机事件及 168 个小时统计桶，仅接受固定枚举与数值；未捕获异常只记录类别与是否主线程，不存消息或堆栈；不含凭据、账号、额度或原始响应。设置提供清除、预览与用户主动系统分享，不自动上传。报告包含版本、网络、省电、系统后台受限标记与后台任务状态；无远程分析、自建服务或自动崩溃上报 |

账号连接的实际权限、续期、服务方适用范围仍由 M0 真机接入验证完成，本政策不替代其验收。当前使用 GitHub Releases 发布实验性 APK；不将隐私说明或构建预发布等同于已通过商店审核或稳定版验收。
