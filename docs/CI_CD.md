# CI/CD 与 GitHub Deployments

2026-10-10 用户新增需求，对应 M6、checklist G01–G08。首版公开渠道已由用户确定为 GitHub Releases APK。

参考 [magpie 工作流](https://github.com/yetone/magpie/tree/599d784f597342e6601bdc1d49b495fdf84e546f/.github/workflows)：预览使用独立环境，构建与发布分离，发布按版本 tag 触发。QuotaPal 将这些边界用于 Android APK，不引入 macOS 公证或模型 API Key。

## 流程

| 入口 | 检查／产物 | 部署 |
| --- | --- | --- |
| PR、master push、手动 CI | 交付脚本测试、71 项 JVM 测试、lint、debug APK；API 29／31／35／36／37.0／37.2 设备矩阵 | PR 或 master 全部通过后登记预览 deployment |
| `v<versionName>` tag，或在该 tag 上手动运行 Release | 验证 tag 格式、版本及主分支祖先关系；复用完整 CI；额外构建和 lint minified release，并在六组模拟器检查真实 release 页面 | 固定密钥签名、校验证书、发布 GitHub Release；记录 `android-release` 环境 |

合并到 master 自动执行完整 CI、release lint／R8 和六组 minified 页面检查；全部通过后使用 android-release 固定密钥签名，上传 `android-signed-apk`（保留 14 天）。运行摘要提供下载链接、版本、源提交和 APK 哈希；产物包含 APK、SHA256SUMS、provenance.json。构建或设备测试失败时不会签名。PR 不接触发布 secrets，master push 随后自动创建独立 `build-<runId>-<attempt>` 预发布并上传相同 APK／校验和／来源信息，历史包不覆盖；同版本 master 产物可用于测试，正式覆盖升级仍需递增 versionCode。

统一状态为 `Android CI gate`，只有交付工具检查、build 与全部设备矩阵成功才会通过。交付工具检查固定 actionlint 1.7.12、运行 ShellCheck 与 Python 测试。主分支 CI 不因后续 push 取消正在运行的验证；PR 新提交可取消旧验证。发布同 tag 串行执行。开发分支通过 PR 验证，避免 push 与 PR 重复启动矩阵；取消整个运行时 gate 跳过，实际构建或测试失败时 gate 仍失败。

## 预览 deployment

`deploy-preview.yml` 在 Android CI 的 `workflow_run` 完成后运行。仅处理成功的本仓库运行，不处理失败、取消、fork 来源、已关闭 PR 或已被新提交替代的运行。PR 关闭后，独立清理工作流将该预览标为 inactive；清理 job 不 checkout PR 代码。它直接使用部署 API 指定被测试的源 SHA，避免 `workflow_run` 自动使用默认分支 SHA 而误记来源。

环境为 `android-preview/pr-<number>` 或 `android-preview/master`。记录 `in_progress`，下载并校验 APK 后记录 `success`；下载或校验失败记为 `failure`。每条记录的 environment URL 指向对应运行的 `android-apk` 下载入口，log URL 指向部署运行。预览是可下载 APK，无需额外开通 Pages。

`android-apk` 包含 `quotapal.apk`、`SHA256SUMS`、`metadata.json`；元数据包括版本号、源 SHA、实际构建 SHA 与 APK 哈希。PR 源 SHA 是分支 head；实际构建 SHA 可以是 GitHub 合并测试提交。预览保留 14 天，过期后重新运行 CI 获取新产物；旧部署记录不意味着下载永久可用。

预览是 debug 签名，供评审测试。GitHub 下载 artifact 可能需要登录。不同 runner 的 debug 签名不保证一致，正式升级使用下述固定发布签名。

有写权限的部署工作流不 checkout 或执行 PR 代码；只读取已完成运行的产物，使用工作流内固定的校验程序。构建任务使用只读 token，不接收发布凭据。

## 发布环境

仓库 Settings → Environments 创建 `android-release`，按组织策略设置允许的 `v*` tags 和发布保护规则。在该环境中配置四个 secrets：

| 名称 | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 现有固定 release keystore 的 Base64 内容 |
| `ANDROID_STORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名 key alias |
| `ANDROID_KEY_PASSWORD` | key 密码 |

Secrets 保存以上四项；不要把密码放到 Environment variables。macOS 下可用 `base64 -i /path/to/quotapal-release.jks | pbcopy` 复制 keystore 的 Base64 内容到剪贴板，然后粘贴到 `ANDROID_KEYSTORE_BASE64`。`ANDROID_STORE_PASSWORD` 填 keystore 密码，`ANDROID_KEY_ALIAS` 填创建时的 alias（例如 quotapal），`ANDROID_KEY_PASSWORD` 填 key 密码；默认 PKCS12 创建方式通常使用同一个密码。

用 `keytool -list -v -keystore /path/to/quotapal-release.jks -alias quotapal` 查看证书（终端交互输入密码）。在 Environment variables 设置 `ANDROID_SIGNING_CERT_SHA256` 为输出 SHA256 后面的完整证书指纹（可带冒号）。指纹不匹配或任一凭据缺失时发布失败。密钥由维护者离线保管和备份，不提交到 Git、不上传到 artifact，不在日志输出密码。不要为每个版本生成新密钥。

发布步骤只在所有测试通过后获取环境 secrets。编译任务不使用签名凭据，签名任务下载 unsigned release APK，以 `zipalign`、`apksigner` 完成对齐、签名和验证。临时 keystore 退出时删除。

master push 会自动生成固定签名 APK；需要手动复验时，在 Actions → Android build and tests → Run workflow 选择 master，勾选 `check-signing`。它会运行完整 CI、构建 minified release，成功后使用 android-release 签名并上传 `android-fixed-signature-check`（保留 14 天），不会创建 GitHub Release。若环境配置了分支／tag 限制，需要同时允许 master 执行此验证及版本 tag 执行正式发布。下载验证产物后核对 provenance 中的源提交、版本、证书指纹和 APK SHA-256；仅配置环境不能证明密钥与指纹匹配。

先更新 `versionCode`（递增）和 `versionName`，经 PR 合并到 master，再创建 `v<versionName>` tag。示例：`versionName = "0.1.0-alpha04"` 对应 `v0.1.0-alpha04`。Release 的手动运行也必须选择已有版本 tag，不能在分支上运行。

最终资产为 `QuotaPal-<version>.apk`、`SHA256SUMS`、`provenance.json`。带连字符的版本标为 prerelease，稳定版本为普通 Release。发布 URL 在 GitHub Deployments 的 `android-release` 中可见。稳定 tag 应在 M4–M5 的真机、长期使用及公开发布验收后创建。

CI debug 签名与 release 签名不同，首次切换需要卸载 debug 包再安装并重新连接；后续 release 版本沿用同一密钥覆盖升级。此配置尚不代替实机升级测试。

## 失败与重跑

- CI 任一步失败或取消：不登记成功预览，不进入签名发布。
- 预览下载／哈希失败：deployment 标为失败，保留对应运行日志。修复后重跑完整 CI。
- 签名缺失／指纹错误：修复环境配置后重跑失败 job，不换 key 绕过指纹校验。
- 发布先建立 draft，全部资产上传后才公开。已有 draft 可重跑上传；已公开版本拒绝覆盖，修复需增加版本并创建新 tag。
- 需要回退时安装已验证的旧 Release；Android 降级限制与数据兼容需单独确认。不要移动已公开版本的 tag。

## 当前验证状态

[PR #16 CI 38037325156](https://github.com/tsonglew/QuotaPal/actions/runs/38037325156)（源提交 a361ded7b96600c5393a48246a4cce1d42a21e35）的交付检查、构建、lint、JVM、API 29／31／35／36／37.0／37.2 全部设备矩阵及统一 gate 已通过，并合并为 master d356cd8。合并触发的 [自动出包运行 38037985183](https://github.com/tsonglew/QuotaPal/actions/runs/38037985183) 已通过 release lint／R8 构建、全部六组设备与 minified 页面流程、统一 gate 和固定签名。产物见 [android-signed-apk](https://github.com/tsonglew/QuotaPal/actions/runs/38037985183/artifacts/11664782203)。

此前 master a983dfd 的 [自动出包运行 38031990526](https://github.com/tsonglew/QuotaPal/actions/runs/38031990526) 已完整成功；实际下载的 alpha15 APK 通过固定证书、来源、版本、哈希和 16 KB 对齐核验。G02／G03 已有通过证据；[正式 alpha15→alpha16 覆盖升级](VALIDATION_ALPHA16.md) 已通过、F02 完成；真实公开 Release 与其 deployment 仍由 G05／G08 验收。

发布控制另有 5 项命令替身测试：上传完成后才公开 draft、证书不匹配、缺失凭据、错误版本均不调用 GitHub，已公开版本不覆盖。替身测试不证明实际证书签名或 GitHub API 发布成功。

GitHub 环境与部署行为参见 [官方环境说明](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments) 和 [部署状态 API](https://docs.github.com/en/rest/deployments/statuses)。

版本发布时，设备矩阵在 debug 回归通过后，下载同次构建的 unsigned release，以一次性测试密钥签名，在隔离模拟器检查启动、示例、组件页、设置、隐私说明和退出。脚本拒绝在真机上执行；通过后才进入正式固定签名 job。日志随设备测试产物保存。此测试不替代 OEM、真实账号或正式签名覆盖升级验收。

手动运行 Android CI 时可勾选 `build-release`，在所选分支验证 minified release 和六组页面流程，不读取正式签名 secrets，也不创建 GitHub Release。用于在合并／打 tag 前排查发布构建问题。

Android 17 使用官方 SDK 的 `37.0/google_apis` 与 `37.2/google_apis_ps16k` 镜像及 4 GB RAM；后者覆盖 16 KB 页面配置。签名脚本显式使用 `zipalign -P 16` 并验证对齐。此处是配置说明，实际结果见对应运行记录。

### Android 17 启动就绪

以下保留排查时序；AVD 元数据修复已在 38007906590 及后续六组 CI 中验证，文中的“待验证”描述对应当时状态。当前结果以上方“当前验证状态”为准。

运行 37986219241 的 API 37.0／37.2 在应用测试开始前分别因 input 服务缺失／Broken pipe 失败；四个既有版本设备测试通过。Android 17 改由 `scripts/android17_device_ci.sh` 启动相同官方镜像，`wait_for_android.py` 要求启动标记、核心服务、包查询、settings 与无操作按键命令连续三轮成功，再运行原有完整测试及可选 minified release 检查。启动／logcat 日志随设备证据上传；失败继续使统一 gate 失败，不跳过检查。该启动流程已在运行 38007906590 的完整矩阵中通过，后续组件刷新失败仍需单独排查。

所有普通工作流 job 检出事件的不可变 `github.sha`，避免设备 job 启动较晚时读到分支新提交；可复用工作流仍支持显式 ref。

### Android 17 模拟器运行环境

API 37.0／37.2 云端可重复出现 SurfaceFlinger 的 ReadColorBufferDma 断言，完整 gate 保持失败。曾固定官方 37.1.11 Stable 并校验下载 SHA-256；本地 ARM64 全套通过，但补 libpulse0 后的实际云端运行仍复现相同断言，因此撤回无效降级，继续使用 SDK 稳定模拟器。系统镜像与完整测试范围保持原样。图形路径的新配置须实际验证后才能认定有效，版本依据与失败对照见 [alpha13 验证](VALIDATION_ALPHA13.md) 和 [alpha14 验证](VALIDATION_ALPHA14.md)。

Android 17 job 显式安装 Linux 运行依赖 libpulse0；初次固定版本对照在缺少 libpulse.so.0 时提前失败，没有执行设备测试，不计作通过。

Android 17 新候选启用官方 Vulkan composition（`-feature VulkanNativeSwapchain -feature GuestUsesAngle`），使用与本地完整 18 项测试通过时相同的参数；verbose 日志保留实际特性值。仅本地 ARM64 已通过，云端 x86_64／16KB 仍待独立验证，不视为 gate 修复完成。

运行 38004158377 显示云端 API 自动识别为 3 后关闭 Vulkan，composition 已开启但无法初始化；候选增加 `-feature Vulkan`，使实际值与本地通过配置的 Vulkan=1 一致。此配置修正仍待新云端验收。

### AVD 主版本元数据对照

显式 Vulkan 候选 38005242748 的两个 Android 17 job 实际 Vulkan=1，但同一 API level: 3 分支仍关闭 HasSharedSlotsHostMemoryAllocator 与 GlDirectMem，SurfaceFlinger 转为 coherent-memory 分配崩溃。依据 [模拟器 AVD target 解析源码](https://android.googlesource.com/platform/external/qemu/+/refs/heads/emu-master-dev/android/emu/avd/src/android/avd/info.c)，新候选只将 CI 新建 AVD 根 ini 的 target 明确设为整数主版本 android-37，保留 system-images/android-37.0/google_apis 与 android-37.2/google_apis_ps16k 原路径。撤回 Vulkan composition／GuestUsesAngle 及逐项强开配置，使用默认 software 图形路径；记录修改前后根 ini、镜像路径、verbose 特性值。就绪后实际检查 guest SDK=37，16KB job 必须实际 PAGE_SIZE=16384。此为源码与日志支持的待验假设，云端结果才证明是否有效。


## GitHub APK 渠道确认（F01）

2026-10-10 用户选择 GitHub Releases 公开 APK。发布使用版本 tag、release notes、APK、SHA256SUMS 和 provenance.json；实验版本标记 prerelease。发布需仓库写权限，先建立 draft 并上传全部资产，再公开，见 [GitHub 官方发布说明](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)。预览 artifact 的 14 天保留期与正式 Release 资产分开说明。

所有 APK 必须签名，后续更新需要维持签名兼容性，见 [Android 官方签名说明](https://developer.android.com/studio/publish/app-signing)。包名维持 com.tsonglew.quotapal，versionCode 递增，固定正式密钥与证书指纹校验使用现有 release job；用户已创建正式密钥并配置 GitHub environment，仍需离线保管。实际固定签名及自动出包已通过，alpha15→alpha16 正式覆盖升级的 F02 也已通过，真实 Release 仍待 G05／G08 验收。

对用户提供 Android 10+ 支持范围、独立第三方身份、实验接入限制、隐私说明、安装／更新步骤和反馈入口。GitHub 版当前不做自动下载或后台安装；用户从 Releases 手动安装，Android 的安装来源权限由系统确认。切换 debug 与正式签名通常需要卸载重装并重新连接，安装前先导出需要保留的诊断报告。


历史失败记录（已由后续修复和验证更新）：PR #9 已合并，主分支 70c4580 的 [运行 38013679974](https://github.com/tsonglew/QuotaPal/actions/runs/38013679974) 因 API 35／37.0 的三组件刷新进度断言失败而 gate 失败，预览被跳过。此前 38007906590 的全绿仅证明对应源提交，不代表最新主分支 gate 通过。当前分支增加诊断时序与页面回归，后续运行通过后再更新 G02／G03。android-release 环境已通过只读 API 确认存在；四项签名 secrets、证书变量和真实发布尚待验证。


### 固定签名验证（不公开发布）

在已合并的 master 上手动运行 Android CI，勾选 check-signing。此选项自动启用 release 构建、release lint／R8 及现有 minified 设备检查，完整六版本 gate 成功后才访问 android-release secrets。签名检查沿用正式签名脚本并校验证书、APK 版本和 16KB 对齐；上传 android-fixed-signature-check（14 天），provenance 标记 mode=signing-check。候选 tag 仅表示版本名称，不证明对应 Git tag 已创建；流程不会创建 tag 或 GitHub Release。使用 android-release environment 的 job 会由 GitHub 生成环境部署记录；该成功记录表示签名验证通过，不代表公开 Release。

手动 check-signing 仅允许 master；master push 会自动执行同一固定签名验证并上传 android-signed-apk。PR／fork／其他分支不能执行带发布凭据的步骤。有固定签名 APK 后可执行真实覆盖安装与数据迁移验收。master push 同时发布构建预发布；稳定版本仍走版本 tag 流程，并满足稳定性验收条件。

### 每次 master 出包自动上传 Releases

2026-10-11 用户授权每次打包上传 GitHub Releases。master push 的完整 CI 与固定签名验证通过后，先保留 Actions artifact，再创建指向本次准确源提交的 `build-<runId>-<attempt>` tag，建立 draft、上传三个资产，全部成功后公开为 prerelease。每次重跑使用新的 attempt；不覆盖旧 tag 或已公开资产。上传失败留下草稿供排查，重新运行失败 job 会产生新的构建条目。PR debug 包及手动 check-signing 仍不公开；稳定版本可继续通过 `v<versionName>` 发布。

### 自动 Releases 实际验收（2026-10-11）

[PR #20](https://github.com/tsonglew/QuotaPal/pull/20) 的最终提交 c0990f1 完整六组 CI 通过，合并为 master 177258647bbd7e78e7b011ca65982ec07e4c53dd。[运行 38109285393](https://github.com/tsonglew/QuotaPal/actions/runs/38109285393) 全部成功，自动生成 [build-38109285393-1](https://github.com/tsonglew/QuotaPal/releases/tag/build-38109285393-1) 预发布，三个资产完整；发布环境状态 success，链接及 tag 源提交正确。

下载 alpha17 APK 后独立核验 versionCode=17、minSdk=29、targetSdk=36、非 debuggable、固定证书和 16 KB 对齐。SHA-256 为 `3639f0ff2e194772215b3192eda8e7687be212e36082db8487e40d65b1fb6af4`，与 GitHub 资产摘要一致。固定证书 SHA-256 为 `5e7980c122100ccf9799a5174cb0e5a1748b8c442c09c015e145003145d2b5d4`。结构化证据见 [自动发布记录](diagnostics/alpha17-automatic-release.json)。

G08 的实际预览与签名 Release 验收完成；这不表示真机、真实账号或长期稳定性完成。G05 的版本 tag 发布另行核验。

### 版本 tag 实际验收（2026-10-11）

[v0.1.0-alpha17](https://github.com/tsonglew/QuotaPal/releases/tag/v0.1.0-alpha17) 指向 master 1772586，与实际 APK versionName／versionCode=17 一致。[运行 38117976113](https://github.com/tsonglew/QuotaPal/actions/runs/38117976113) 完整六组设备、gate、签名及公开预发布成功。三个附件已重新下载，哈希、远端摘要、固定证书、来源及 16 KB 对齐通过；APK 与同源自动构建发布的哈希完全一致。deployment 6992019277 为 success，环境链接指向该版本 Release。G05 完成，结构化证据见 [版本发布记录](diagnostics/alpha17-version-release.json)。
