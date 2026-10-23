# 首版自用测试版验证记录

验证日期：2026-10-09。版本：`0.1.0-alpha01`。Android 10+，仅支持单 Codex 账号。

本次完成首版实现与自动验证，尚未完成 Android 真实账号首次授权、实际 token 续期、OEM 桌面和连续自用验收。接入仍标记实验性，不作为公开发布完成。

## 可复核证据

| 项目 | 结果与来源 |
| --- | --- |
| 实现提交 | `cde15c19e8254778e142c6ca4d7d8ff25b7933a6` |
| CI | [37936883382，全部成功](https://github.com/tsonglew/Quodex/actions/runs/37936883382) |
| 构建 | App 与设备测试包编译成功，debug APK 生成成功 |
| 单元测试 | 27 项通过，0 失败 |
| lint | 通过，0 错误；依赖新版本及旧平台忽略属性等提示保留在报告中 |
| Android 设备测试 | Pixel 6 配置／Android 35 模拟器，4 项通过，0 失败 |
| 原生组件 | 140×150、280×150、140×230 dp；每种布局验证额度和退出清理；更新时间完整可见 |
| 视觉检查 | [7 张原生截图](SCREENSHOTS.md)，均为明确标注的示例数据 |
| APK 完整性 | ZIP CRC 检查通过，65,207,208 bytes |

2026-10-09 按要求统一历史提交身份为 `Tsonglew <tsonglew@gmail.com>`。上表实现提交对应 CI 原提交 `99a399f0cd9ced0ef359d0c6e3f3816a17f3de50`；已核对改写前后代码树完全一致，现有 APK 与测试结果不变。

APK SHA-256：

```text
6cccef0589f8c407099a312b09ac88f6ef09423d8bc6925d3a41620fa586b1ef
```

本地交付位于 `artifacts/QuotaPal-0.1.0-alpha01-debug.apk`，校验文件在同目录。CI 的 `android-build` 产物包含同版 APK、单元测试及 lint 报告；`android-device-tests` 包含设备测试、截图及诊断，产物保留 14 天。APK 和完整报告未加入 Git，示例截图已随文档保存。

## 测试覆盖与实际限制

- 单元测试覆盖缺失／非法额度、单／多窗口、账号隔离、重置时间、401／403／429／5xx、网络断开、退避、重定向不转发凭据、并发刷新、旋转续期与退出旧响应。
- Android 测试覆盖示例页面、标签切换、浅深色、退出、Keystore 加密与篡改拒绝、凭据删除、备份关闭、独立组件配置，以及真实 RemoteViews 渲染与清理。
- 截图与测试使用合成示例账号和数据，不含真实身份或凭据。界面中的时间按测试设备时区显示；本次模拟器为 UTC。
- 原生宿主验证不等同于真实 Launcher 验收；系统添加与取消配置、点击刷新、多实例请求量、进程回收、重启、升级和 TalkBack 继续保留待验收。
- APK 使用 CI debug 签名，仅用于此次自用验证。后续稳定更新需要固定签名，当前不承诺不同 CI 构建可直接覆盖安装；签名与迁移仍在 M5 中跟踪。

## 接下来的人工验收

按 [TESTING.md](TESTING.md) 执行真实设备连接与组件操作，记录机型、系统和 Launcher。设备码登录需先在 ChatGPT 安全设置或工作区权限中开启。48 小时后台观察与连续 7 天自用必须实际完成后再勾选。

M1 已关闭；M0、M2、M3、M4、M5 的未完成条件继续由 [GitHub milestones](https://github.com/tsonglew/Quodex/milestones) 和 [checklist](DEVELOPMENT_CHECKLIST.md) 跟踪。
