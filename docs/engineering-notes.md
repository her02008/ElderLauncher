# 工程约束与踩坑笔记

这份文档记录**改这个项目时不能破的约定**和**已经踩过的坑**。功能说明看 [README](../README.md)，
协议看 [remote-config-protocol.md](remote-config-protocol.md)，语音/「恰了么」看
[design-voice-alive.md](design-voice-alive.md)。

新加功能前先扫一遍「一、硬约束」和「五、坑点」，能省掉一整轮调试。

---

## 一、硬约束（改了会出事）

### 首页色块
- **`home_blocks` 键不存在 = 首次运行，给默认列表；键存在但值 `[]` = 用户主动全删了，不要再塞回默认。**
  这两者混淆过一次，表现为"用户删光色块，重启又全回来了"。
- 一屏 9 格：`≤9` 全显示；`>9` 只显示前 `VISIBLE_BLOCKS = 8`，第 9 格变成「更多」（`+N`）→
  `BlockMenuDialog` 列出 `homeHidden()`。判断一律走 `hasMore()/homeVisible()/homeHidden()`，别手写长度比较。
- 上限：`MAX_GRID_SLOTS = 9`、`MAX_BLOCKS = 30`、`MAX_SUB_ITEMS = 9`（二级不再嵌套）。
- 新增内置色块要**四张表一起补**：`ORDER` / `ICONS` / `LABELS` / `COLORS`。漏一张会 fallback 成设置图标，
  而且不报错，只能靠眼睛发现。
- `columnsFor()`：0/1→1 列、2→4→2 列、其余 3 列，入参是 `min(size, 9)`。
- 高度上限 180dp，但**不低于 `minBlockCellHeight()`**，否则字被裁成半截。

### Activity / 布局
- 区域顺序：`activity_main` 是 `NestedScrollView → LinearLayout(homeRoot) → headerRow + sectionSos/Grid/Quick`。
  **这三个 section 必须是 `homeRoot` 的直接子 View**，`applySectionOrder()` 靠摘下再 `addView` 重排。
- 顶部只有时间 + 日期，**不要加问候语**。日期用 `Calendar` 手拼（`updateDate()`），
  **不能用 TextClock 的 `EEEE`**（会出英文 "Sunday"）。
- 透明 Activity（`Theme.Elder.Translucent`）**绝对不能配 `screenOrientation`**，
  Android 8 会抛「Only fullscreen opaque activities can request orientation」。
- 返回键用 `onBackPressedDispatcher`，不要 override 已废弃的 `onBackPressed()`。
- `ChannelsBridgeActivity` 里的 **WebView 必须 INVISIBLE**：一旦可见，微信播放器就滑不动、返回键退回中转页。

### 密码与安全
- 设置页分两级：**改桌面形态 / 涉系统权限的只放 `AdminActivity`**（密码门），
  字号、语音开关、快捷拨号、紧急联系人、板块顺序免密码。
- `SettingsActivity` 里必须保留 `rowAdminEntry`，删了 `AdminActivity` 就成了孤儿页（进不去）。
- 重置暗号 `*#0454#*`，**结尾必须是 `#*`**：`*#...*#` 是 MMI 形态，系统会弹英文报错。
  判断统一用 `AdminGate.isResetCode()`（内部已经 strip 空格和短横线），别写 `== RESET_CODE`。
- `DialActivity.onCall()` 的判断顺序不能反：**先判重置暗号，再拦所有 `*`/`#` 开头的号码**。
- 远程设置的**危险指令必须走本机确认**（`remote_pending` + `RemotePrompt`），不能直接落地；
  `DANGER_TTL_MS = 24h` 过期作废。未知 op 一律拒绝，任何时候都不下发可执行代码。

### 远程同步
- `rev` 用**秒级时间戳 - 1600000000**。绝不能把毫秒时间戳当 rev —— `JSONObject.optInt` 会截断成负数，
  结果是每次同步都把历史指令重跑一遍。
- **一条指令一个键**（`cmd:{code}:{id}`）。早期用 `cfg.cmds` 数组是读-改-写，多个子女同时下发必丢一条。
- `AliveCenter.schedule()` 的开启条件必须是「「恰了么」开着」**或**「已绑定远程」，两者任一。
  只判前者的话，绑了远程但没开恰了么的用户永远不同步，表现为「绑成功了，但怎么改都没反应」。
- `RemoteCenter.sync()` 执行前要跳过「回执队列里已有的 id」——服务端 rev 抖动或 ack 丢包会重发同一条。
- 指令必须**幂等**（`set_font` 是"设成某个值"，不是"加一级"），否则重发就不安全。

### 构建
- **Gradle 8.5 不支持 JDK 25**。Android Studio 自带的 JBR 是 25 的话直接失败，要指到 JDK 17/21。
- 两个 Gradle 实例并发构建会损坏 Kotlin 增量缓存（报一堆找不到符号），
  先 `gradle --stop` 再删 `app/build/kotlin`。
- 布局里**不要写 8 位内联色值**（`#14FFC0392B`），这版 aapt2 会拒；挪到 `colors.xml`。

---

## 二、模块地图与要点

| 模块 | 文件 | 要点 |
|------|------|------|
| 首页 | `MainActivity` | 时间/日期、区域排序、色块网格、快捷拨号、未接来电弹窗入口 |
| 色块模型 | `util/HomeBlock.kt` | `HomeBlock` / `HomeAction` / `BlockType` / `BlockCatalog`（内置 11 个） |
| 执行器 | `util/ActionExecutor.kt` | 所有色块点击的落地：内置动作 / 启动应用 / 小程序 / 快捷方式 |
| 设置 | `SettingsActivity` | 免密码项 + `rowAdminEntry` |
| 高级设置 | `AdminActivity` | 密码门 + 10 张功能卡片（视觉规范见下） |
| 密码 | `util/AdminGate.kt` + `PasswordActivity` | 盐 + SHA-256，只存进程内存的解锁态，5 分钟 TTL |
| 拨号 | `DialActivity` | 自带键盘，不走系统拨号；`*#` 号码特殊处理 |
| 语音 | `util/PinyinMatcher.kt` + `VoiceCommand` + `VoiceActivity` | `Transliterator` 逐字转拼音，阈值 0.62 |
| 恰了么 | `util/AliveCenter.kt` + `AliveAlarmReceiver` | 1 小时自续闹钟，短信最多 3 条 / 间隔 12h |
| 跌倒 | `util/FallDetector.kt` + `FallActivity` | 加速度计状态机，见 README 已知限制 |
| 未接来电 | `service/MissedCallListenerService` + `MissedCallCenter` | 只能靠通知使用权 |
| 视频号 | `service/ElderAccessibilityService` + `ChannelsBridgeActivity` | 无障碍自动点，四道网抓 weixin:// |
| 远程 | `util/RemoteClient/RemoteCenter/RemoteCommand` + `RemoteActivity` | HMAC 签名，白名单 8 条 |
| 快捷拨号 | `QuickContact` + `QuickIconStore` | 头像优先级 iconPath > photoUri > color |

### 高级设置页的视觉规范（2026-10-28 定，加新卡片照抄）
- **功能入口**：彩色图标（白填充 + `app:tint` 着色）+ 加粗标题 + 状态小字 + 右侧箭头，整行可点。
  样式四件套 `AdminEntryRow / AdminEntryIcon / AdminEntryTitle / AdminEntryState` 在 `styles.xml`。
- **提示说明**：浅灰圆角框 `AdminHintBox`（`bg_hint_box.xml`），不可点，不参与点击。
- 两者视觉上必须一眼能分开 —— 之前全是平铺灰字，用户反馈"看不出哪里能点"。

### 添加色块的五条路径（`HomeBlocksActivity`）
「添加什么？」五选一：0 内置（`available` 要**排除 WXAPP 和 CHANNELS**，它们有自己的入口）/
1 其他应用 / 2 小程序（多选，带中性「管理小程序」，返回后自动重弹，空列表也要提示去管理）/
3 视频号（目标层没有就加一块，一级加完跳 `ChannelsLinkActivity`）/ 4 桌面快捷方式。

多选一律**差异同步**：`pickItems()` / `syncPickedItems()` + `usedKeys(blocks, type)`。
注意 **APP 与 MINI 的键空间是分开的**，别共用一个 set。

---

## 三、几个「看着能简化，其实不能」的地方

- **桌面快捷方式必须自己实现**。Android 8+ 的 `requestPinShortcut()` 不自己弹框，而是去**默认桌面**里找
  声明了 `android.content.pm.action.CONFIRM_PIN_SHORTCUT` 的 Activity 并拉起它。没有这个入口，
  微信/浏览器的「发送到桌面」会静默失败（日志：`Launcher doesn't support requestPinnedShortcut()`）。
  `ShortcutPinActivity` 因此**不能加密码门**（一插密码链路就断了），它只做「确认 + accept() + 加色块」。
- **视频号不能用 scheme**。`weixin://channels` 从微信 6.6.7 起就无效了；`channels.weixin.qq.com` 的链接在
  外部只提示「请在微信客户端打开」。稳定路径只有无障碍点「发现 → 视频号」。
  **不要再加 scheme 试探**：`resolve()` 成功但 `startActivity()` 静默失败，会把逻辑带进死胡同。
- 视频号失败时**必须走 `openChannelsFallback()`**，不要回头再调 `openChannels()`（会死循环）。
- 小程序**不要走「自造 pin shortcut + 中转页」**那条路，试过，微信那边不配合，已删除，别再来一遍。
- 微信小程序的打开顺序是有讲究的（`ActionExecutor.openMiniProgram`）：
  `wxshortcut://` → `#小程序://名称/口令`（猜 `weixin://dl/business/?t=`）→ channels 链接 →
  `weixin://` 原样开（最可靠，但要后台声明明文 Scheme）→ http(s) 交系统 → 兜底 `?username=`。
- `AliveCenter.touch()` 里已报警的必须**强制写 `lastActive` 再 `recover()`**，否则会在"报警/恢复"之间来回横跳。
- `TtsManager` 两个坑必须保留：① 引擎未就绪时把播报内容存 `pending`，`onInit` 后补念（冷启动第一下）；
  ② `status ≠ SUCCESS` 必须 `shutdown()` 并把 `tts` 置 null —— 否则 `init()` 因为 `tts != null` 早退，
  **整场会话永久静音**。
- 语音页**不要开 TTS**，自己的播报会被麦克风收进去，触发二次识别。

---

## 四、数据与状态

| 存储键 | 内容 | 备注 |
|--------|------|------|
| `home_blocks` | 色块 JSON 数组 | 存在但为空 = 用户全删 |
| `home_order` | 三个 section 的顺序 | 逗号分隔，读时过滤非法/重复并补齐 |
| `quick_contacts` | 快捷拨号（≤12） | 头像图片在 `filesDir/quick_icons/` |
| `wx_mini_apps` | 小程序（≤20） | 旧键 `wx_mini_program` 一次性迁移 |
| `missed_calls` | 未接来电（≤10，24h） | 只删本地记录，不动系统通话记录 |
| `admin_salt` / `admin_hash` | 密码 | 16 字节随机盐 + SHA-256 |
| `remote_*` | 远程绑定与回执 | 随 `clearAll()` 一起被恢复出厂清掉 |

- 所有键走 `PrefsManager`，不要在 Activity 里直接 `getSharedPreferences`。
- 恢复出厂：`clearAll()`（用 `commit()` 不用 `apply()`，要立刻生效）+ `QuickIconStore.clearAll()`
  （**只删自己的私有目录**，不能碰用户原来的相册图；删除前先 `isOwned()` 判断）。
- 图片导入：长边压到 ≤512 再拷进私有目录；删除前必须 `isOwned()`，
  否则用户选的是相册原图会被误删。

---

## 五、坑点清单（都真踩过）

**UI / Adapter**
- Adapter 里禁止 `val it = items[position]` —— `setOnClickListener` 的隐式 `it` 会把它遮蔽，用 `val item`。
- **Adapter 持有外部 list 时，`update()` 绝不能把 holder 那份原样传回**：内部 `clear()` 会把外面那份一起清空。
  这是「快捷拨号改完要重启才生效」的真凶。
- `BlockRowAdapter` 必须持有外面那个 list **实例**，不能自己再 `toMutableList()` 一份。
- 弹窗里不要「落盘后重新反序列化」再操作对象：`HomeBlock` 会变成过期引用，`indexOf` 永远 -1。
- 跑马灯 + `gravity=center` 会两头切字：先切 `START|CENTER_VERTICAL` 再 `isSelected`，
  溢出判断放 `post{}` 里，并且要校验 text 没被复用改掉。
- `fitIcon()` 压图标：下限 26dp，**上限取 `R.dimen.icon_normal`，不要读 `layoutParams.height`**
  （复用时越压越小，最后缩成一点）。
- 复用 `item_home_button.xml` 要显式设 `layoutParams`（原根是 match_parent，放 wrap_content 容器里量不出高度）。
- `AlertDialog` 自定义 view 不会自己滚动：自己包 `ScrollView`，高度用「屏高 - 260dp」封顶。
- 「打电话 / 发短信」那种大按钮用 **`LinearLayout` 竖排「图标 + 文字」**，不要用 `Button`/`MaterialButton`
  （会吞掉背景，圆角/颜色全失效）。

**Android 平台**
- 包名别猜：`ShortcutManager` 在 `android.content.pm`，`AlarmClock` 在 `android.provider`。
  **`CATEGORY_APP_CLOCK` 不存在**（只有 `CATEGORY_APP_CALENDAR`），"打开时钟"没有官方 Intent。
- `ShortcutQuery` 只能用 `setPackage()` / `setQueryFlags()`，没有公共字段可写。
- Android 12+ 震动要 `VibratorManager`，`VIBRATOR_SERVICE` 已废弃。
- 鸿蒙 4 的 `RoleManager` HOME 弹窗会**静默失败**，设默认桌面只能跳系统「默认应用」页。
  **即使已经是默认桌面也要照常跳转**，否则那个入口变成死键。
- 未接来电只能靠通知使用权解析通知文案，各家 ROM 写法不同 —— 高级设置里留了诊断入口（列最近 8 条原文）。
- `READ_CALL_LOG` 自 Android 10 起对非默认电话应用是硬限制，不要幻想用它做兜底。

**远程服务端**
- Workers 的 KV 是**最终一致**的；Node 版用 `store.json` + 临时文件 rename 原子替换。两边语义要对齐。
- 子女端设备清单存在浏览器 `localStorage['elder_devices']`，主键用浏览器侧 `id`，
  判重必须用 `endpoint + code`（同码跨服务端曾互相覆盖）。

---

## 六、构建 / 版本 / 签名 / CI

```bash
export JAVA_HOME=/path/to/jdk-17      # 或 21，别用 25
gradle assembleDebug --console=plain  # 仓库没提交 gradle-wrapper.jar
```

- 版本号唯一来源是根目录 `version.properties`。**打包类任务会把 `versionCode` 和 `versionName` 各 +1
  并写回文件**；`gradle tasks` / `clean` 不会动。tag 构建会带 `ELDER_NO_VERSION_BUMP=1` 跳过自增，
  保证 `v1.0.47` 里装出来就是 1.0.47。本地等价开关：`gradle assembleDebug -PnoVersionBump`。
- 签名固定为仓库内的 `signing/elder-launcher.jks`（公开密码，仅供自用）。原因：CI 每次都是全新虚拟机，
  AGP 会随机生成 debug key，导致每版签名不同、手机无法覆盖安装。CI 有「核对签名指纹」步骤守着。
- CI（`.github/workflows/build-apk.yml`）：push main / PR / 手动 dispatch 编译；push tag `v*` 出正式 Release；
  main 的构建会覆盖更新 `latest` 预发布。产物是公开直链，不需要登录。

---

## 七、改代码的自检清单

1. `gradle assembleDebug` —— 目标 **0 error / 0 warning**（warning 不修会越积越多，最后淹掉真问题）。
2. 动到首页布局：9 格边界（0/1/8/9/10 个色块）、三种 section 顺序、字号切超大都要点一遍。
3. 动到色块/密码：删光色块重启、连错 3 次密码、`*#0454#*` 重置两条路都走一遍。
4. 动到远程：多子女同时下发、断网重连、危险指令 24 小时作废。
5. 动到 `app/build.gradle.kts`：确认 `version.properties` 递增后 CI 仍能解析。

---

## 八、换环境 / 远程继续维护

这个项目被刻意做成「编译和发版都不依赖某台机器」，换环境成本很低：

| 环节 | 依赖 | 换机器要做什么 |
|------|------|----------------|
| 编译 & 出包 | GitHub Actions | 什么都不用装，push 就有包 |
| 发版 | GitHub Releases | 网页上点一下（或 push 一个 tag） |
| 改代码 | 一份 git 工作区 | clone 下来即可；本地编译要 JDK 17/21 + SDK 34 |
| 签名 | 仓库内 `signing/elder-launcher.jks` | 天然一致，不会签出不同的包 |

**新环境起步**：

```bash
git clone git@github.com:her02008/ElderLauncher.git
export JAVA_HOME=/path/to/jdk-17          # 别用 25
gradle assembleDebug --console=plain     # 或用 Android Studio 直接打开
```

`local.properties`（`sdk.dir`）不入库，Android Studio 打开时会自动生成。
若某台机器 `github.com:22` 时通时拒，可参考本仓库历史做法走 `ssh.github.com:443`。

**手机上远程改代码**：WorkBuddy 的「微信客服号集成」可以让手机微信直接给电脑上的 WorkBuddy 下指令
（右上角头像 → 助理设置 → 微信客服号集成 → 扫码绑定）。执行发生在**你自己的电脑上**，用的就是本机的
项目文件、Shell 和 git 凭证，所以 push 不需要重新配 SSH key。前置条件是电脑开机、WorkBuddy 在运行；
远程任务固定使用「助理」专属文件夹作为工作目录，项目不在那儿时在指令里给绝对路径。

> 因为编译在 CI，远程改完只要 push，剩下等 Actions 出包即可，不必在本机等几分钟编译。
> 唯一的本地依赖是「电脑要开着」；完全不依赖本机的做法是企业智能体（云端 Linux 沙箱 + Git 仓库工作区），
> 但那是企业坐席功能。

