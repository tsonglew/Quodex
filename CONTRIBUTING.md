# 参与 Quodex

欢迎报告问题、改进说明与翻译、补充设备验证，或提交聚焦的代码修改。项目处于 Alpha 阶段，优先保证数据准确、账号隔离和桌面展示可靠。

## 报告问题与提出建议

使用 [Issue 表单](https://github.com/tsonglew/Quodex/issues/new/choose)。先搜索已有问题，并附上 App 版本及来源、Android／机型／Launcher、复现步骤、预期和实际结果。后台或组件问题请补充最后成功时间、是否强停、网络与省电状态。

可在设置 → 问题排查中预览并主动分享诊断报告。不提交真实 API key、Cookie、访问令牌、授权码、账号缓存或未脱敏响应。截图中也应遮挡个人信息。

较大的界面、接入协议或数据模型变更建议先在 Issue 中说明用户场景；小修复和文档改进可以直接提交 PR。中文和英文反馈均可。

## 开发环境

1. Fork 并克隆仓库，从 `master` 创建独立分支。
2. 安装 JDK 17、Android SDK Platform 36 和 Build Tools 35.0.0。设置 `ANDROID_HOME`，或在未提交的 `local.properties` 写入 `sdk.dir=/你的/Android/sdk`。
3. 使用仓库内的 Gradle Wrapper：

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

构建版本以 `build.gradle.kts`、`app/build.gradle.kts` 和 `gradle/wrapper/gradle-wrapper.properties` 为准。不提交 keystore、SDK 本地路径或 IDE 状态。

| 修改范围 | 建议验证 |
| --- | --- |
| 纯文档、截图、GitHub 配置 | 检查相对链接、资源和 YAML；涉及工作流运行 `actionlint`。无需因此启动完整设备矩阵。 |
| 解析、仓库、联网或账号状态 | 运行相关 JVM 测试及 lint；覆盖未知数据、失败、取消、限流和账号隔离。 |
| Compose、Glance 或桌面图标 | 构建并验证相关设备用例，核对深浅色、大字体和可用点击区域，附示例数据截图。 |
| 发布与交付脚本 | `python3 -m unittest discover -s scripts/tests -v`、`actionlint`、`shellcheck scripts/*.sh`。 |

设备测试使用专用模拟器，不在日常账号环境执行清理、退出、强停或安装测试。完整说明见[测试文档](docs/TESTING.md)。

## 提交 PR

- 说明具体问题、触发方式与修改后的行为，关联对应 Issue。
- 保持改动聚焦；不要混入无关重构、格式化或版本升级。
- 写清实际验证结果和未覆盖的范围，不把模拟器或合成数据验证当作真实账号验收。
- 用户可见变化需同步 README、相关文档及[官网](https://tsonglew.github.io/quodex/)。官网源码在 `tsonglew/tsonglew.github.io` 的 `public/quodex/`，在 PR 中关联同步 PR。
- 截图注明版本、设备来源和示例数据；开发中、已合并、已发布分别说明。

[AGENTS.md](AGENTS.md)记录仓库交付约定。[CI](docs/CI_CD.md)保留完整设备检查与独立签名发布边界；PR 不使用发布密钥，依赖更新也不自动合并。

## 品牌与翻译

SVG 与 Android 图标由 `python3 scripts/generate_brand_assets.py` 统一生成，修改生成器后重新生成并检查小尺寸和裁切。具体规范见[品牌文档](docs/BRANDING.md)。

翻译请保持简体中文、英文和日文资源的占位符、格式与语义一致，避免把未知或旧数据翻译成确定状态。[本地化说明](docs/LOCALIZATION.md)

## 许可证

项目采用 [MIT License](LICENSE)。提交代码或素材时，请确认你有权贡献，并保留所使用第三方内容要求的署名与许可说明。
