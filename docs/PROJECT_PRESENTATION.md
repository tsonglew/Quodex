# 项目展示与 GitHub 配置

2026-11-03。README 作为下载与贡献入口，官网作为产品展示，历史验证集中到文档导航；新功能交付仍遵循 [AGENTS.md](../AGENTS.md)。

## 参考与取舍

- [LocalSend 官网](https://localsend.org/)与 [README](https://github.com/localsend/localsend/blob/main/README.md)：一句话说明用途，下载和平台要求靠前，贡献指南独立。
- [Immich 官网](https://immich.app/)与 [README](https://github.com/immich-app/immich/blob/main/README.md)：真实产品截图、明确的功能范围和文档入口。
- [LocalSend Issue 表单](https://github.com/localsend/localsend/blob/main/.github/ISSUE_TEMPLATE/bug_report.yml)：结构化收集版本、环境与复现步骤；Quodex 加入 Launcher、后台状态和凭据脱敏提示。

保留 Quodex 品牌与原生截图，不引入参考项目的文案、商标或用户数据。许可证由维护者选择为 MIT，见 [LICENSE](../LICENSE)。

## 仓库内配置

| 文件 | 用途 |
| --- | --- |
| `README.md` | 定位、截图、下载、能力与边界、本地开发、参与入口和 MIT 许可。 |
| `docs/README.md` | 使用、开发与历史记录导航。 |
| `CONTRIBUTING.md`、`.github/SUPPORT.md` | 贡献流程、按改动选择验证和求助入口。 |
| `.github/ISSUE_TEMPLATE/` | 问题／建议表单与帮助链接；使用现有 `bug`、`enhancement` 标签。 |
| `.github/pull_request_template.md` | 问题、结果、验证、README 与官网同步；保留原交付要求。 |
| `.github/dependabot.yml` | 每周检查 GitHub Actions 与 Gradle；限制并发 PR，相关依赖分组，人工评审合并。 |
| `.github/release.yml` | GitHub“生成发行说明”按既有标签分类，保留未分类内容。 |

Glance 1.1.1 有原生布局覆盖与构建保护，Dependabot 不自动升级 `androidx.glance:*`；需连同布局、构建检查和设备验证一起维护。生成发行说明配置不会替代现有签名发布脚本的版本、来源与 APK 元数据。

GitHub 配置语法依据：[Issue forms](https://docs.github.com/en/communities/using-templates-to-encourage-useful-issues-and-pull-requests/syntax-for-issue-forms)、[Dependabot](https://docs.github.com/en/code-security/reference/supply-chain-security/dependabot-options-reference)、[自动发行说明](https://docs.github.com/en/repositories/releasing-projects-on-github/automatically-generated-release-notes)。

## 远端展示

仓库 About 使用 Android 额度与订阅查询的准确描述，官网链接保持 `https://tsonglew.github.io/quodex/`。主题包括 `android`、`kotlin`、`jetpack-compose`、`codex`、`widgets`、`usage-monitor`，并覆盖已有服务名称；移除原来的 `andoird` 拼写错误。

Issue／Dependabot／发行说明等仓库文件在合并到默认分支后生效。官网源码仍为另一个仓库的 `public/quodex/`，通过原 GitHub Pages 工作流部署；本任务不迁移托管，也不修改签名发布门禁、权限或密钥。

## 本次验证

- README、贡献指南、文档导航和品牌文档的本地链接与图片引用检查通过。
- Issue 表单与 Dependabot 的 YAML／字段检查通过；`actionlint` 通过。未运行完整 Android 设备矩阵。
- 新版品牌资源在最新主分支基础上完成 `:app:assembleDebug`、`:app:lintDebug`；SVG 与 Android 资源继续共用生成器。
- 官网检查 1440 px 桌面、390 px 手机与 320 px 窄屏；本地资源、锚点、历史深色预览切换与 `#motion` 深链接通过，保留减少动画设置与手动视频播放。
- 官网 `astro check` 无错误；完整站点构建在博客 `/archives` 渲染远程图片时失败，图片来自未修改的博客文章。Quodex 静态页面可独立预览，此结果不代表完整 Pages 构建通过或已经部署。

远端补充验证：[官网 PR #10](https://github.com/tsonglew/tsonglew.github.io/pull/10) 新增不部署的 PR 构建检查，固定 Node 22 与 npm 11.11.0，保留 `npm ci`。修正 npm 10 对既有锁文件的 peer dependency 解析差异后，[运行 41619677125](https://github.com/tsonglew/tsonglew.github.io/actions/runs/41619677125) 完整成功；验证提交为 `b5d0c74a76bc577e075d85c1489bd3980cd9baac`。本地博客图片失败不代表远端构建失败。

配套 [App PR #47](https://github.com/tsonglew/Quodex/pull/47)。官网应在 App PR 合并、MIT 与贡献文档链接可用后合并；合并后仍须核对 Pages 工作流和线上页面。
