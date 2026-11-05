# 全界面玻璃材质（alpha36）

alpha36 将页面卡片、设置分组、提示、页头、侧栏和 Token 热力图桌面组件加入同一玻璃材质。兼容主分支的总览／账号／紧凑设置导航，保留各面板的字号、图标、间距和圆角；API 赠送额度卡是本次新增功能。

页面背景使用现有主题的蓝／紫颜色生成柔和渐变。卡片共享单独记录的背景层，不捕获自身，也不逐卡片进行 GPU 像素采样；弹窗仍采样真实页面。API 33+ 使用边缘折射与色散，API 31–32 使用模糊高光，API 29–30 使用实色。小于 32dp 的表面不使用边缘透镜。错误色、交互反馈和图表数据配色保留。所有桌面组件使用静态玻璃位图，不读取桌面壁纸。

浅色、深色浓度继续独立保存；减少透明效果和系统高对比度使用实色。对共享渐变在两种主题、0／1／1.5 浓度下检查主要、次要、强调与错误文字的 4.5:1 对比度。

页面覆盖：`ServicesOverview`、账号分组、`QuotaOverview`、`TokenUsageCard`、`ResetCreditsCard`、`ApiCreditsCard`、`SubscriptionsPage`、`SettingsPage`、`AppUpdates`、`WidgetPage`、`DesktopIconSettings`、`BackgroundGuide`、`PanelHeading` 和 `QuodexApp` 页头／侧栏。共用 `GlassPanels.kt` 与 `GlassMaterial.kt`。

## alpha35 历史实现与验证记录


This changes overlay materials and adds appearance controls. Existing layout, spacing, typography, icons, corner radii, copy, touch targets, and business behavior are preserved. This alpha35 integration is based on current master; the existing floating glass navigation, subscriptions, reset cards, heatmaps and localization remain in place.

## Surface audit

| Surface / file | Background | Treatment |
| --- | --- | --- |
| Login, operation error, diagnostic report/error / `QuodexApp.kt` | App page | Glass dialog; rim refraction and color split on API 33+ |
| Update dialog / `InAppUpdateDialog.kt` | App page | Same; preserves download status, progress and buttons |
| Refresh explanation, sign-out confirmation / `SettingsPage.kt` | Settings page | Glass dialog |
| Release notes / `QuotaOverview.kt` | Quota page | Glass dialog |
| Daily token detail / `TokenUsageCard.kt` | Quota page | Glass dialog |
| Privacy notice / `PrivacyNotice.kt` | Settings page or login dialog | Glass over the page; solid when nested over another dialog to avoid replaying the wrong background |
| Manual widget instructions / `WidgetPage.kt` | Widget page | Glass dialog |
| Brand selector / `BackgroundGuide.kt` | Settings page | Basic glass; no lens or color split during the native popup animation |
| Standard, compact and large-font widgets / `QuotaWidget.kt` | Unreadable launcher wallpaper | Static tint, highlight and rim with a contrast floor; no wallpaper capture, blur or refraction |
| Quota/token cards, settings panels, desktop-icon settings, update cards, widget layout panels/previews, welcome panels, Notice | Flat color | Keep existing solid material |
| Floating navigation / `FloatingNavigation.kt` | Scrolling page | Preserve the existing glass navigation; reduce-transparency and high-contrast preferences now make its background opaque |
| Header, navigation rail, heading icon backgrounds | Flat color | Keep solid |
| Subscription connection/reset / `SubscriptionsPage.kt`, home-pin help / `HomePinPermissionGuide.kt` | App page | Same glass dialog material, preserving localized content and controls |
| Token heatmap widget / `TokenHeatmapWidget.kt` | Launcher wallpaper | Retain its existing solid calendar surface and semantic heatmap colors |
| System toasts, permission screens, installer, launcher add confirmation | System-owned windows | Keep system material |

## Implementation

`Theme.kt` retains the existing color scheme and provides a separate glass environment. `GlassTokens.kt` owns the three material states, fixed ink colors, density, luminance hysteresis, contrast checks and shared dimensions. Components opt in through wrappers around the original Material AlertDialog and DropdownMenu; Material still owns their layout, semantics and interaction.

The existing page root records a Compose GraphicsLayer. Each overlay replays only the page background at its screen coordinates. Text and controls are drawn afterward and remain sharp. The implementation does not persist page screenshots or use Android cross-window blur.

Sampling clips fifteen individual pixels on the GPU at a 5×3 grid, then reads back only those pixels. Adaptation trims two extremes from each end; contrast checks retain all samples. Updates are coalesced for 120ms, and closed menus stop sampling. Light mode uses 0.45/0.55 luminance hysteresis; dark mode always uses light ink. Each fixed surface ID caches one ink Boolean per theme, never pixels or account data. There are no adjacent glass cards requiring a shared row decision.

| Platform or condition | Rendering |
| --- | --- |
| API 33+ | Blur/color correction, rounded-rectangle AGSL rim displacement and ±5% RGB split |
| API 31–32 | Blur/color correction and a softly inset tint; no AGSL lens |
| API 29–30, missing/unreadable background, nested dialog | Solid surface |
| Reduce transparency or API 36+ high-contrast text | Solid surface, no lens or highlights |
| Launcher widget | Cached static bitmap with a contrast-safe tint |

The rim follows `min(44dp, short side / 3.5)` and the requested power curve. Nonuniform/custom shapes and surfaces below 32dp skip the lens. Each surface compiles its shader once. Uniforms, size, position and source revisions rebind the native RenderEffect because Android snapshots shader parameters and caches rendered inputs. Native screenshot tests caught and now cover stale-background caching on both rendering paths.

Device screenshots showed that a ±15% color split creates haze around page controls, so it is reduced to ±5%. Refraction remains at the bevel; the center stays flat.

### Readability tradeoff

The requested light secondary ink is already near 4.5:1 on pure white. Keeping the entire dialog at a 6–38% tint would fail contrast on busy backgrounds. The material therefore reaches a denser, contrast-safe core before the existing 24dp content padding begins, while retaining a clear rim. Black/white detail checks protect against page glyphs between the fifteen sample points. This can make the light center effectively opaque. Raising density takes effect immediately; thinning can animate.

Contrast checks include primary/secondary text, the existing primary/error colors, and the inner highlight. Foreground blend modes remain normal to preserve native error colors and controls. Original focus and disabled states are retained. The two sliders never override the readability floor, even at zero.

The Glance bitmap cache is bounded to 4MB, with dimensions capped at 1024 pixels. Standard and compact widgets request the host's exact available size, retaining their existing content breakpoints while rendering the material for that aspect ratio. This preserves the existing 20dp/28dp radii and rim thickness after resizing instead of stretching a preset bitmap. No new recurring background work is added. Widget accessibility state is evaluated when the widget renders; launcher/OEM behavior still needs coverage beyond the devices listed below.

## Settings

Open **Settings → Appearance → Glass material** (设置 → 外观 → 玻璃材质). All new labels and accessibility descriptions are translated into English, Simplified Chinese and Japanese.

- Light and dark tint values are independent, range from 0 to 1.5, and default to 1.
- Dragging previews the real dialog immediately. Finishing a drag saves through the existing DataStore and updates widgets.
- **减少透明效果** (Reduce transparency) switches to an opaque surface.
- API 36+ uses the public high-contrast text API and listener. Older systems have the in-app control; no hidden settings are read.
- Compose animations follow the system duration scale; there is no continuous material animation.

## Files changed

Added:

- `app/src/main/java/com/tsonglew/quodex/data/GlassPreferences.kt`
- `app/src/main/java/com/tsonglew/quodex/ui/GlassTokens.kt`
- `app/src/main/java/com/tsonglew/quodex/ui/GlassMaterial.kt`
- `app/src/main/java/com/tsonglew/quodex/ui/GlassEffects.kt`
- `app/src/main/java/com/tsonglew/quodex/ui/GlassOverlays.kt`
- `app/src/main/java/com/tsonglew/quodex/ui/GlassSettings.kt`
- `app/src/main/java/com/tsonglew/quodex/widget/WidgetGlassMaterial.kt`
- `app/src/test/java/com/tsonglew/quodex/ui/GlassTokensTest.kt`
- `app/src/androidTest/java/com/tsonglew/quodex/GlassMaterialDeviceTest.kt`
- `docs/LIQUID_GLASS.md`

Modified:

- `app/src/main/java/com/tsonglew/quodex/MainViewModel.kt`
- `app/src/main/java/com/tsonglew/quodex/data/Storage.kt`
- `app/src/main/java/com/tsonglew/quodex/ui/Theme.kt`
- `app/src/main/java/com/tsonglew/quodex/ui/QuodexApp.kt`
- `app/src/main/java/com/tsonglew/quodex/ui/SettingsPage.kt`
- `app/src/main/java/com/tsonglew/quodex/ui/BackgroundGuide.kt`
- `app/src/main/java/com/tsonglew/quodex/ui/InAppUpdateDialog.kt`
- `app/src/main/java/com/tsonglew/quodex/ui/PrivacyNotice.kt`
- `app/src/main/java/com/tsonglew/quodex/ui/QuotaOverview.kt`
- `app/src/main/java/com/tsonglew/quodex/ui/TokenUsageCard.kt`
- `app/src/main/java/com/tsonglew/quodex/ui/WidgetPage.kt`
- `app/src/main/java/com/tsonglew/quodex/widget/QuotaWidget.kt`
- `app/src/androidTest/java/com/tsonglew/quodex/WidgetRenderTest.kt`

## Historical validation of the original implementation (2026-11-04)

These results describe the original pre-integration build, not alpha35 or the current PR.

- Debug APK, instrumentation APK, release/R8 build: successful (`final-readable-build.log`, `widget-frame-build.log`). The release APK is unsigned; nothing was published.
- Unit tests: **177 passed, 0 failed/skipped**. Includes busy/unknown background contrast, missed details between grid points, ink hysteresis, invalid preferences and opaque accessibility fallback.
- Lint: **0 errors** for debug and release. Existing warnings remain; the new material adds two nonfunctional KTX style suggestions.
- Pixel 6 / API 37: **8 device tests passed** on the final production build (`pixel6-readable-final.txt`). Covers independent settings persistence, recreation/theme switching, reduce-transparency semantics, changing native backdrop pixels, unchanged foreground bounds, actions, and existing app flows. Both themes were inspected in native screenshots. The temporary app/test installations were removed afterward.
- API 29 emulator: **7 device tests passed** on the final build and updated capture helper (`api29-readable-final.txt`): solid fallback/settings plus all five widget rendering tests. The earlier PixelCopy backing-surface failure passes after waiting for frame commit.
- API 31 emulator: **7 device tests passed** on the final production build (`api31-readable-final.txt`): glass behavior plus all five widget rendering tests, including missing-quota states, multiple sizes/themes, and large fonts.
- Earlier Pixel 6 UI-experience checks passed; the wide-window test is conditional and was not exercised on the phone. OEM launchers, foldables, rotation, performance profiling and screen-reader interaction are not claimed as verified by this run.

The widget screenshot helper now waits for a committed window frame before PixelCopy. Measured child layout alone did not guarantee a backing surface on API 29. No visual/layout assertion was removed.

The final device runs total **22 passing test executions** across API 29, API 31 and Pixel 6/API 37. Earlier long-running emulator sessions stalled during broader test runs; those incomplete logs are retained and are not counted as passes. Final runs were performed separately after restarting the isolated emulators.

Raw logs, retained failures, before/after screenshots and APKs are under `artifacts/glass-validation/`. Final screenshots are in `final-readable/`; the deliverable is `final/quodex-glass-debug.apk`. Its SHA-256 is `468adb397641269a9882388b3a0b4aa3944dfb877d010187e72ed7356baa1c08`. The striped backdrop exists only in the device-test fixture. Both isolated emulators were stopped after validation.

## Alpha35 integration (2026-11-05)

The PR preserves current master and adds localized settings, the shared material to the newer subscription/home-pin dialogs, and the accessibility override to the existing glass dock. The quota widgets receive the static material; the heatmap widget retains its solid calendar background. `compileSdkVersion("android-37.0")` names the installed SDK target explicitly, avoiding the older plugin looking for nonexistent `android-37`; minimum and target API levels remain 29 and 36.

The file list above describes the original material change. Integration also changes `app/build.gradle.kts`, the English/Chinese/Japanese string catalogs, `FloatingNavigation.kt`, `HomePinPermissionGuide.kt`, `SubscriptionsPage.kt`, README, this document, the screenshot index, and the documentation index. Local verification of alpha35: **254 JVM tests passed**; Debug/Release Lint each reported **0 errors** (68/69 warnings), Debug and instrumentation APKs and the minified Release APK built successfully. The explicit SDK target still produces minSdk 29, targetSdk 36 and compileSdk 37 in the APK manifest.

The API 37 emulator run reported 13 cases: **12 passed, 1 wide-window assumption skipped**. It covers glass behavior/settings, App flows, two-times font scale and navigation reachability. System UI displayed its own ANR overlay during that run; those screenshots are retained only as local diagnostics. Both glass-specific tests then passed again (2/2), with clean material screenshots, after one explicit Wait action on the known system dialog. The product ANR checks and assertions were not suppressed.

Review caught preset bitmap stretching when a widget is resized beyond its responsive bucket. The fix uses exact host dimensions for quota widgets; the real-host resize/delete/re-add test now includes 140×400dp and checks the rendered background aspect ratio. That test passed on API 37 (1/1), followed by successful unit tests, Debug/Release Lint and all three APK builds for the final code.

[Light settings](screenshots/glass-alpha35/glass-settings-light.png) and [dark settings](screenshots/glass-alpha35/glass-settings-dark.png) are native, unedited alpha35 debug screenshots from the API 37 arm64 emulator in local demo mode. Tint values are 40% light and 130% dark. Logs are under `artifacts/glass-pr/` in the isolated PR worktree. GitHub's six-platform device matrix is checked separately before merge.
