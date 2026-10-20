# 小米桌面组件触摸验证

日期：2026-10-20。候选：`0.1.0-alpha19`，分支 `codex/fix-xiaomi-widget-touch`；手机正式安装基线：`0.1.0-alpha18`。本文区分手指触摸、无障碍动作与自动化输入，不代表全部 OEM 或正式签名升级已验收。

## 环境

Xiaomi 17 Ultra，Android 17／API 37；系统 `OS4.0.0.22.XPACNXM`，OS 名称 `OS4.0`，MIUI 兼容属性 `V816`；桌面 `RELEASE-8.01.02.6325-260818-09181716-R`。独立 preview 包使用示例数据，正式包及真实账号未被测试覆盖或清除。

## 实现

保留 Glance 1.1.1。打开区域使用 Glance Button 的直接 PendingIntent 绑定路径，覆盖原可见内容；刷新为相邻的独立动作，保留原 `RefreshWidgetAction` 与请求去重逻辑。标准、slim 和大字体布局均明确划分打开与刷新区域，控制尺寸使用系统 SP 转换与字体度量。

Android 12 及以上的 12 个 Glance Button XML（base 1 个、v33 11 个）仅将根改为透明、可点击的 TextView，并显式居中及暴露无障碍语义。保留原 ID、宽高、weight、layout gravity、layoutDirection、Glance 标记及 ripple。这样沿用 Glance 的动作与 ID 分配，避免试验中嵌入 RemoteViews 的目标映射异常。`verifyGlanceButtonLayouts` 检查 debug/release 实际依赖均为同一 Glance 1.1.1 AAR，覆盖集合完整，且原布局元数据未改变；升级依赖必须重新审查。

打开区域提供额度、状态、更新时间等完整摘要，避免触摸探索只读出“打开 QuotaPal”。当前外层 4–12 dp 留白不在打开区域内；文字、主体、页脚时间与独立刷新区为验收对象。API 29/30 继续使用 Glance 的模拟按钮分支。

## 当前候选验证

| 检查 | 结果 |
| --- | --- |
| v5 Debug／androidTest 构建、JVM、lint | 构建成功，71/71 JVM 测试及 `lintDebug` 通过。后续正常 preview 构建也通过同时检查 debug/release 的资源保护任务。 |
| v5 API 29 | 16/16，通过打开 7 项、刷新 5 项、布局 4 项；107.7 秒，31 张截图。包括页脚时间中心真实 DOWN/UP，打开 MainActivity 且无新增 HTTP 请求。 |
| v5 API 37 | 正在执行；不能用之前 Button 候选的结果代替最终 TextView 资源覆盖。 |
| v5 TalkBack | 待运行真实 input-filter 专项。 |
| 独立直接叶子探针的手指触摸 | 用户确认去掉灰底后，顶部标题／额度能打开应用。此探针曾有页脚错位，仅作为顶部路径对照。 |
| 完整生产实现 preview | 已构建，正在验收标题、时间、刷新及外观；尚不宣称全部通过。 |
| 正式签名覆盖升级 | 未执行。 |

v5 固定 APK 位于本地忽略目录 `artifacts/xiaomi-alpha18/emulator-widget-fix-v5/apks/`：app SHA-256 `7f89a559994ca0a00144db838bf6918822e89e4108a916b3923fe4aa60c79c63`；androidTest SHA-256 `7d469770ed2fc45d6aee07e631a954e7c00eab31434c823ac02cca3276a5082d`。普通设备测试检查真实触摸、MainActivity、实际 TextView 更新、单 HTTP 请求、刷新进度和刷新不打开应用；API31+ 另检查精确 TextView 类、有效且唯一的动作 ID、互不重叠的动作范围。跨版本以相应提交的 CI 为准。

## 实机对照与外观排查

| 对照 | 已观察结果 |
| --- | --- |
| 原 alpha18，Glance 1.1.1 | 手指点标题不能打开；对标题所属可点击父容器执行无障碍 ACTION_CLICK 可以打开。 |
| 独立 Glance 1.1.1／1.2.0 | 普通 clickable 标题都经用户确认失败；升级依赖没有解决。普通“设置”图标可以点击。 |
| BUTTON／OVERLAY 探针 | 原生 OPEN 按钮手指可点；将空按钮覆盖标题后，标题也能手指打开。 |
| 完整 Button preview | 用户确认标题打开、刷新留在桌面；自己的诊断记录打开与刷新事件，示例模式为 SKIP_DEMO。存在三个灰色胶囊背景。 |
| 透明背景对照 | 显式透明颜色、tint、透明 foreground 覆盖及非零 `#01000000` 背景/tint 均没有消除桌面的灰色；未采用这些失败候选。 |
| 同机标准 AppWidgetHostView | 相同浅色／深色组件没有灰色；Button 背景与 tint 实际为 0，未按下/聚焦。修改 forceDarkAllowed 或清空 foreground 不改变截图。浅色独立宿主测试 1/1 通过。 |
| 直接 TextView 探针 | 顶部恢复透明且用户手指确认能打开；嵌入 RemoteViews 的页脚与刷新被桌面映射到同一 uniqueId，故未采用该嵌入方案。 |

普通文本节点自身 `clickable=false` 不能证明失败，因为动作可能在父容器。无障碍 ACTION_CLICK 不等价于触摸分发。ADB 坐标输入在此机未能可靠操作应用控件，不能仅据其无响应判断组件失效。原生 RemoteViews SINGLE／MULTI 的早期探针只测过无障碍动作，不计入手指触摸证据。

只读检查已安装桌面 APK 发现 Flutter AOT、`flutter_android_equiv`／`native_widget_sdk` 以及 RemoteViews 动作转换符号。桌面日志确实收到 preview 的点击与更新，并为三个 Button 加载 `#00000000` drawable，没有对应 unsupported/error。这支持存在自定义渲染路径，但不足以确认具体命中测试或灰底生成函数。APK、字符串、手机截图与原日志只保存在本地忽略目录，不随代码发布。

## 保留的失败与证据校正

- v2 API37 首轮被 SystemUI ANR 打断；下一轮 14/15 的失败发生在字体配置传播前置条件。夹具先启动前台 Activity 并严格等待系统字体配置生效后，固定 v2 APK 整轮 15/15（223.390 秒）通过。该结果不覆盖最终 TextView 资源修改。
- v3 API29 首轮 14/15：200% slim 在触摸前显示“已用 38%”，另两个组件显示“剩余 62%”。新建测试组件未初始化自己的偏好，可能读取旧 ID 设置。夹具改为明确设置并仅清理自己创建的 ID；未清应用数据、未修改生产删除路径或删除专项。v4 15/15（108.5 秒）通过，v5 新增页脚项后 16/16 通过。
- [AOSP UiAutomation 源码](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/app/UiAutomation.java) 区分普通输入注入与 `injectInputEventToInputFilter`；普通注入跳过 accessibility input filter。因此 alpha10–alpha13 及早期“TalkBack 双击通过”只能视为普通注入刷新，不能证明 TalkBack 识别了手势。
- 更正输入入口后的 v2 探针实际收到触摸探索开始事件，额度中心却只聚焦“打开 QuotaPal”，确认摘要回归。补充完整摘要后，v3 TalkBack 两项 2/2（60.637 秒）通过：默认字体 standard/slim 探索能读出 62%；200% 字体两组件探索刷新、双击产生点击事件、状态与无障碍树变为 83% 且各一次请求。没有普通注入 fallback。测试后字体 1.0、服务 null、touch exploration 0 已恢复。
- v3 slim 的一张更新截图仍捕获旧一帧 62%，不作为 83% 视觉证明。专项使用请求、存储和无障碍树断言；普通刷新回归另等实际 TextView 更新。v3 新摘要下打开应用 6/6（63.617 秒）通过，亦不代替最终 v5 验收。

历史证据按 v2／v3／v4／v5 分目录保留，不覆盖失败日志。后续补充完整 preview 手指结果、API37／TalkBack、CI 与正式升级结果后再判断交付。
