# MEMORY.md — ElderLauncher 长期笔记（2026-10-26 整理）

## 概况
- 安卓长辈桌面 `com.elder.launcher`，Kotlin + ViewBinding（无 Compose）。compileSdk/targetSdk 34、minSdk 24、JVM 17。源码 `app/src/main/java/com/elder/launcher/`。
- Activity：MainActivity、HomeBlocksActivity、HomeOrderActivity、QuickDialsActivity、WxMiniActivity、ChannelsLinkActivity、ChannelsBridgeActivity、ShortcutPinActivity、DialActivity、SettingsActivity、AdminActivity、PasswordActivity、SosActivity、ContactsPickerActivity；除 ChannelsBridgeActivity 外均继承 BaseActivity（长辈字号）。
- util：HomeBlock(HomeAction/BlockType/BlockCatalog)、ActionExecutor、AppUtils、WxMiniApp、WxShortcuts、PinnedShortcuts、QuickContact、QuickIconStore、AdminGate、PrefsManager、FontSizeManager、TtsManager、TorchManager、PermissionHelper、PinyinMatcher、VoiceCommand、RadioLauncher、ContactFinder、AliveCenter、SmsSender、MissedCall(Center)。
- ui：HomeAdapter、BlockCell、BlockMenuDialog、BlockRowAdapter、QuickContactAdapter、QuickDialRowAdapter、WxMiniRowAdapter、MissedCallDialog。service：ElderAccessibilityService、MissedCallListenerService。

## 主页色块
- 一屏 9 格：≤9 全显示；>9 只显示前 `VISIBLE_BLOCKS=8`，第 9 格变「更多」（`+N`、ic_more）→ BlockMenuDialog 列 `homeHidden()`。统一用 `hasMore()/homeVisible()/homeHidden()`。
- MAX_GRID_SLOTS=9、MAX_BLOCKS=30、MAX_SUB_ITEMS=9（二级不再嵌套）。
- 类型：BUILTIN(+HomeAction) / APP(包名) / MINI(标识存 pkg) / SHORTCUT(`shortcut://包名/ID`)。
- 存 `PrefsManager.getHomeBlocks()/setHomeBlocks()`，key `home_blocks`。**键不存在=首次运行给默认；存在但 `[]`=用户全删，别再塞默认。**
- 内置 10 个：PHONE/CONTACTS/DOUYIN/WXAPP/CHANNELS/CLEAN/REBOOT/TORCH/WIFI/DATA/VOICE；**ORDER/ICONS/LABELS/COLORS 四张表一起补**（漏了 fallback 成设置图标）。
- 列数 `columnsFor()`（0/1→1、2→2、4→2、其余 3），入参 `min(size,9)`；高度上限 180dp 但不低于 `minBlockCellHeight()`。
- 多选（其他应用/小程序/快捷方式）都是差异同步 `pickItems/syncPickedItems`；`usedKeys(blocks,type)`，**APP 与 MINI 键空间分开**。
- 管理页 ↑↓（首尾置灰 0.25f）+ ItemTouchHelper 长按拖动（`onMove` 改 list 再 notifyItemMoved，`clearView` 只 persist）。**BlockRowAdapter 必须持有外面那个 list 实例**。`refresh()=persist()+adapter.submit()`。

## 区域顺序（SOS/功能块/快捷拨号）
- `home_order` 逗号分隔，`SECTION_KEYS` 即默认；get 过滤非法/重复并补齐。不设密码。
- activity_main：NestedScrollView → LinearLayout(homeRoot) → headerRow(topBar + btnSettings) + sectionSos/Grid/Quick。**三个 section 必须是 homeRoot 直接子 View**。divider 在 quick 内部。
- 顶部只显示时间 + 「x月x日 星期x」，**无问候语**；日期用 Calendar 手拼（updateDate），**不用 TextClock 的 EEEE**（出英文）；format12/24Hour 都 `HH:mm`。
- clockTime→`openClock()`（AlarmClock.ACTION_SHOW_ALARMS）、tvDate→`openCalendar()`（CATEGORY_APP_CALENDAR）。都要 clickable + selectableItemBackground；左边距 = topBar paddingStart 12dp + 子 View 4dp。
- `applySectionOrder()` 摘下再 addView，`appliedOrder` 比对防无谓重排；quick 第一时隐藏 divider。

## 快捷拨号
- 主页只显示+拨打，增删改在管理页（长按头像→编辑/移除）。`QuickContact(name,phone,photoUri,iconPath,color)`，key `quick_contacts`，MAX_ITEMS=12。
- 头像优先级 iconPath > photoUri > color 圆底+首字；color==0 按位置取 PALETTE。
- QuickIconStore：importImage 长边≤512 拷 `filesDir/quick_icons/`；circle* 带 LruCache。**删除前必须 isOwned()**。
- 编辑弹窗用文件内私有 `QuickDraft`/`EditSession` 保住草稿（跳通讯录/相册会回来），取消编辑删掉本次导入图。
- 点头像不直接拨：`dialog_quick_action.xml`（canceledOnTouchOutside）左绿「打电话」右蓝「发短信」，96dp「图标+文字」竖排 **LinearLayout（别用 Button，MaterialButton 吞背景）**，`findViewById<View>`。
- 防误触：OnScrollListener 记 `lastQuickScrollAt`，`QUICK_TAP_GUARD_MS=350ms` 内刚滑完不响应。
- 点击即 TTS 播报名字（没名字念号码）；试听放在编辑弹窗 `rowQuickSpeak`（列表行右侧已有 ↑↓×，塞喇叭会挤换行）。
- `sendSms()`：ACTION_SENDTO + `smsto:`（带号码、不静默发送），失败退 ACTION_VIEW `sms:`。

## TtsManager
- 单例，ElderApp.onCreate init()；开关 `isVoiceEnabled`（默认开）。**两个坑必须保留**：① 引擎未就绪时播报存 `pending`，onInit 后补念（冷启动第一下）；② status≠SUCCESS 必须 shutdown() 且 tts=null，**否则 init() 因 tts!=null 早退 → 整场会话永久静音**。
- LANG_MISSING_DATA 退 Locale.getDefault() 并打日志。只读短句，QUEUE_FLUSH。

## 设置页分工
- 原则：改桌面形态/涉系统权限的只放 AdminActivity。
- SettingsActivity：字号 / 首页板块顺序 / 快捷拨号 / 紧急联系人 / 语音播报 / rowAdminEntry / 关于。**不要**再加回功能块、系统权限、手机桌面。
- AdminActivity（密码门，onCreate+onResume 双校验+touch）：主页功能块 / 手机桌面 / 无障碍 / 设备管理员 / 未接来电 / 还在吗 / 改密码 / 恢复默认 / 锁定 / 底部重置暗号。**必须保留设置页 rowAdminEntry**，否则孤儿页。
- 「添加什么？」五选一：0 内置→pickBuiltin（**available 排除 WXAPP 和 CHANNELS**）；1 其他应用；2 小程序→pickMinis（多选带中性「管理小程序」，registerForActivityResult 回来后自动重弹；空列表也提示去管理）；3 视频号→pickChannels（目标层没有就加一块，一级加完跳 ChannelsLinkActivity）；4 桌面快捷方式→读 PinnedShortcuts.all，空则教用户去点「添加到桌面」。

## 桌面快捷方式（pinned shortcut）
- **必须自己实现**：Android 8+ 的 requestPinShortcut 不自己弹框，而是去**默认桌面**找声明 `android.content.pm.action.CONFIRM_PIN_SHORTCUT` 的 Activity。找不到就整体失败（`isRequestPinShortcutSupported()` false，调用方静默），日志 `Launcher doesn't support requestPinnedShortcut()`。
- `ShortcutPinActivity`（Theme.Elder.Translucent，继承 BaseActivity）：`LauncherApps.getPinItemRequest` → 校验 isValid + REQUEST_TYPE_SHORTCUT → 确认 → `accept()` → 加 SHORTCUT 色块 + toast。**不设密码**（插密码就断链）；位满不加块但快捷方式本身已成。
- `PinnedShortcuts`：身份 `shortcut://<包名>/<id>`（第一个 `/` 分隔）。`all(ctx,pkg=null)` 用 ShortcutQuery + FLAG_MATCH_PINNED（不设 package=全包）；`start()` 用 `la.startShortcut`；图标带 LruCache 退宿主图标。**ShortcutQuery 用 setPackage()/setQueryFlags()，无公共字段**；`getShortcuts(query,user)` 两参版 + @Suppress。
- `WxShortcuts` 只是薄封装（`wxshortcut://<id>` 历史格式不能改），**别再往里加实现**。
- 渲染 BlockCell/BlockRowAdapter 有 isShortcut 分支，类型名 `block_kind_shortcut`。
- ⚠️ 固定关系按桌面应用记（isPinnedByCaller），换桌面/重装要重发。⚠️ API 24/25 的老广播未实现。

## 微信小程序
- `WxMiniApp(id,name,target)`，`wx_mini_apps`，上限 20；旧键 `wx_mini_program` 一次性迁移。WxMiniActivity 增删改+↑↓+拖动；编辑弹窗有「粘贴」和 Neutral「试一试」。
- openMiniProgram 顺序：① `wxshortcut://`→WxShortcuts（读不到提示+开微信首页）② `#小程序://名称/口令`→抠口令猜 `weixin://dl/business/?t=`（再 ?ticket=）→失败教用户发聊天里点 ③ channels 链接→走视频号 ④ `weixin://` 原样开（**最可靠**，需后台声明明文 Scheme，非个人主体）⑤ http(s) 交系统 ⑥ 其余→`?username=`。
- **自造「pin shortcut + 中转页」被用户否定后已删，别走回头路**。
- WXAPP 色块：0 个开微信 / 1 个直开 / 多个弹 BlockMenuDialog；统一微信绿 + ic_wxapp。

## 视频号（CHANNELS）
- **scheme `weixin://channels` 无效**（微信 6.6.7 起移除私有 scheme），稳定路径是**无障碍自动点**。
- `ElderAccessibilityService.openChannels()`：拉起微信 → `steps=["发现","视频号"]` 状态机（只认 com.tencent.mm 的 WINDOW_STATE/CONTENT_CHANGED，步间隔 600ms，单步 12s 超时）。`clickByLabel()` DFS rootInActiveWindow + windows[].root 精确匹配 text/contentDescription，退「前缀且长度≤+2」，取最深；`clickNode()` 先点自己再找可点击父（≤6 层）最后按坐标 dispatchGesture。
- 找不到「发现」=在聊天详情→BACK ≤3 次。**两个保险**：拉起后 1800ms 内不按；只有前台包名是 com.tencent.mm 才按。
- **禁止再加 scheme 试探**（resolve 成功但 startActivity 静默失败 → 误判）。链路末端必须是可见动作。
- MainActivity：有链接直接走链接，无链接且无障碍没开才弹引导。`openChannels()` 失败必须 `openChannelsFallback()`（**别再回 openChannels，死循环**）→ 无障碍 → openWeixinHome+toast。
- `ChannelsBridgeActivity`：**WebView 必须 INVISIBLE**（否则播放器不能滑、返回键退页）；四道网 = shouldOverrideUrlLoading 拦 weixin:// + onConsoleMessage 正则 + JS 找 a[href^=weixin://] / 扫 outerHTML / fetch 页面脚本 + 派发 MouseEvent。探针 700ms×7 + 7s 兜底；拿到后 Java 侧原生 startActivity。失败带 EXTRA_CHANNELS_HINT 回桌面（onCreate 与 onNewIntent 都要处理）。
- 微信对外链就是不配合：channels.weixin.qq.com 只在客户端内有效，外部只提示「请在微信客户端打开」。
- ChannelsLinkActivity：粘贴（自动抠 https://）/试一试/保存/清除。

## 大键盘拨号
- 主页「拨打电话」→DialActivity（不用系统拨号）；样式 DialKey/DialActionKey。有 CALL_PHONE 直接 ACTION_CALL，否则申请后退 ACTION_DIAL。数字键 TTS 播报；tvNumber 跑马灯。
- `onCall()`：**先判 `AdminGate.isResetCode()`，再拦所有 `*`/`#` 开头号码**（否则按 MMI 处理弹英文报错）。顺序不能反。
- 底部左 1/3 通讯录 + 右 2/3 拨打电话（84dp）。通讯录按钮是 LinearLayout+ImageView+TextView（bg_contacts_btn）→ ContactsPickerActivity → 号码填框不直拨。

## 手机桌面
- intent-filter 两个：MAIN+HOME+DEFAULT 与 MAIN+LAUNCHER；`stateNotNeeded="true"`。
- 返回键：`onBackPressedDispatcher` + `isDefaultHome()`，是默认桌面就吞掉，否则 isEnabled=false 后重派。别 override 废弃的 onBackPressed()。onNewIntent 滚回顶部。
- 入口 AdminActivity `rowAdminHome` → openHomeSettings()（ACTION_HOME_SETTINGS 退 ACTION_SETTINGS），onResume 刷文案。**已是默认桌面也照常跳转**（否则变死键）。
- **鸿蒙 4 的 RoleManager HOME 弹窗静默失败，不要用**。纯净模式会拦侧载。

## 管理密码
- 定位：防误操作，不是防黑客。AdminGate 只存随机 16 字节盐 + SHA-256（admin_salt/admin_hash）。
- `RESET_CODE="*#0454#*"`（**结尾必须 `#*`**，`*#...*#` 是 MMI 形态会弹英文报错；老码 `*#0454*#` 与 ENTRY_CODE 已删，别加回）。判定统一 `AdminGate.isResetCode()`（strip 空格/短横线），别写 `== RESET_CODE`。
- PWD_LENGTH=6、FAILS_BEFORE_RESET=3、UNLOCK_TTL_MS=5min。解锁状态只存进程内存；touch() 在 onResume 顺延。
- PasswordActivity 三模式 SETUP/CONFIRM/VERIFY（Mode.RESET 已删）；设置阶段显数字、校验阶段显 ●，满 6 位自动提交。`require(activity,target)` 是统一守门人，由密码页自己拉起目标页。
- 要密码 = AdminActivity 全部 + HomeBlocksActivity（onCreate+onResume 双校验）+ openBlockEditor()；免密码 = 字号/语音/紧急联系人/快捷拨号/首页顺序。
- 重置两条路：① 拨号 `*#0454#*` → `dialog_dial_reset.xml`（**CheckBox cbResetAll + 确定/取消，setView 塞进弹窗，不是 setItems**）：不勾只 clearPassword()；勾选弹二次确认后 clearPassword + `PrefsManager.clearAll()`（commit 不用 apply）+ `QuickIconStore.clearAll()`（只删私有目录）。② 密码页「忘记密码？」连错 3 次出现，**只重置密码**。

## 构建
**Gradle 8.5 不支持 JDK 25**；Android Studio 自带 jbr 是 25 会失败。项目没有 gradlew：
```bash
cd /d/Workspace/Codes/2026_Working/0930-ElderLauncher
export JAVA_HOME=/c/Users/ruok/.jdks/jbr-21.0.11
/c/Users/ruok/.gradle/wrapper/dists/gradle-8.5-bin/5t9huq95ubn472n8rpzujfbqh/gradle-8.5/bin/gradle assembleDebug --console=plain
```
增量 6–20s。SDK 在 `D:\Install_Programs\Android\SDK`（只有 android-34/37.0）。过滤 `grep -a -E "\[version\]|BUILD|^e: |^w: "`。目标 0 error / 0 warning。

## 版本号
- 根 `version.properties` 唯一来源。`app/build.gradle.kts` 配置阶段读；任务名（取 `:` 后最后一段，避免 generateDebugBuildConfig 误判）以 assemble/bundle/install/package 开头或 =build → +1 并**先写回文件再打包**。失败构建也吃号。
- `buildFeatures.buildConfig = true`；设置页读 BuildConfig，文案 `settings_about_version`。

## 未接来电（方案 B：回桌面弹一次）
- **只能靠通知使用权**（READ_CALL_LOG 自 Android 10 对非默认电话应用是硬限制）。MissedCallListenerService；`android:label` 会出现在系统勾选列表。没开=完全收不到，不做 READ_PHONE_STATE 降级。
- `MissedCall(number,name,time,count,notified)`，key `missed_calls`。同号合并、**同一条通知重复投递（<SAME_EVENT_MS=3s）不加次数**；24h 过期、最多 10 条。
- 触发：MainActivity.onResume → maybeShowMissedCalls()；服务新未接时 `MissedCallCenter.onNew`（onResume 挂、**onPause 置空**）立刻喊一次。只弹 notified==false，弹完 markNotified。
- MissedCallDialog：1 人行 + 大「回拨」；≥2 人列表（MAX_ROWS=3，每行自带回拨）+「全部知道了」，**处理一个人不关整窗**。「知道了」只删本地记录不动系统通话记录。canceledOnTouchOutside(false)。
- 诊断：高级设置里长按列最近 8 条电话类通知原文，ROM 写法不一照这个补正则。Kotlin `when` 是关键字，参数不能叫 when。

## 语音 /「还在吗」（v1.0.25）
- 详见 `docs/design-voice-alive.md`。**拼音不用第三方库**：tinypinyin 拉不到，改 `android.icu.text.Transliterator`（Han-Latin，API 24+），**逐字转换**保音节边界；`ü` 特判成 v。阈值 **0.62**（正样本 0.78+，负样本 0.21~0.28）。
- `matchOne` 顺序不能改：① 先 extractCallName（否则「打电话给儿子」被「打电话」1.0 抢走）② 内置命令打分 ③ ≥0.9 直接返回 ④ 最后试「打开 X」。
- 语音页**不要 TTS**（会被麦克风收进去）。「救命」→ 全屏红页 3 秒倒计时**默认执行**。
- 还在吗：AliveCenter + SmsSender + AliveAlarmReceiver；活跃来自无障碍事件 / USER_PRESENT+SCREEN_ON / 主页 onResume；`AlarmManager` **1 小时一次自续排**，BootReceiver 重排；预警走短信最多 3 条、间隔 12h，可暂停 1/3/7 天，恢复发「已恢复」。
- ⚠️ touch() 里已报警的**必须强制写 lastActive 再 recover**（否则来回横跳）。⚠️ 无障碍没开基本失效。
- `HomeAction.VOICE` 在老用户机器上不会自动出现，要手动添加。
- 子女端远程设置**已实现**，见下节。

## 跌倒检测（FallDetector，2026-10-27 实现）

- **高级设置 UI 规则**（2026-10-28 定）：功能入口 = 彩色图标 + 加粗标题 + 状态小字 + 箭头整行
  可点；提示说明 = 浅灰圆角框 `AdminHintBox`。样式四件套在 `styles.xml`（AdminEntryRow/Icon/
  Title/State + AdminHintBox），图标白填充 + `app:tint` 着色。改高级设置页照这个规则加。

- `util/FallDetector.kt`（进程级单例，`ElderApp.onCreate` init）+ 全屏 `FallActivity`
  （showWhenLocked/turnScreenOn，透明主题，**不加 screenOrientation**）。
  开关 `PrefsManager.isFallEnabled`（默认关），管理页 `rowAdminFall` 卡片。
- 原理（纯加速度计，无新权限）：`<3 m/s²` 持续 ≥100ms（自由落体）→ 600ms 内 `≥30 m/s²` 尖峰
  → 等 2s 稳定取低通重力基准 → 偏转 >40° = 被捡起。**阶段推进靠内部 Handler 循环，
  不能靠页面驱动**（响铃前页面不存在）。
- 流程：撞击 → **60s** 没捡起 → 响铃+震动+fullScreenIntent 弹页 → 再 3 分钟没回应 →
  **短信 + 隔 8s 直接拨打** `AliveCenter.notifyPhone()`（沿用「还在吗」号码）。
  **家人回拨自动接听**：`ACTION_PHONE_STATE_CHANGED` 收 RINGING 且在 ALERT →
  26+ 有 `ANSWER_PHONE_CALLS` 走 `acceptRingingCall()`，兜底 `KEYCODE_HEADSETHOOK` 媒体键。
  冷却 10 分钟。
- **电量自适应响铃**：>50% 持续响；21–50% 或省电模式 响30s/停60s；≤20% 响10s/停120s。
  tick 每秒查相位切 `startAlarmSound/stopAlarmSound`，电量现查（充电自动升档）。
- **预警短信带当前电量**（`fall_sms_battery`），读不到电量退 `fall_sms`；`batteryPct` 失败=-1
  按「充足」处理。管理页跌倒卡片有**红字耗电警告**（`fall_battery_warning`，色值进 colors.xml
  ——layout 内联 8 位 hex 会被这版 aapt2 拒）。
- 省电：只在亮屏时注册加速度计；观察期/响铃期例外（熄屏也听，否则发现不了被捡起）。
- 「我没事」和返回键都算机主回应。SEND_SMS 复用 AdminActivity 的 `smsPermission`，
  靠 `smsRequestForFall` 标记区分回调归属。Android 12+ 震动走 `VibratorManager`。
- **通话退出机制**：ALERT 中电话 RINGING/OFFHOOK → 静音（inCall 守卫 tick 不复响、不发短信）；
  通话结束结算——**来电**接通 ≥15s → 退出预警；太短/没接通 → 恢复响铃、可再自动接听。
  退出只认来电：去电 OFFHOOK 从拨号就算，没打通也有 30s+ 时长，按去电退出会误判。
- 局限：软面（床/沙发）可能测不到；进程被杀即停。阈值真机待调。

## 远程设置（子女端，2026-10-26 实现，v1.1）

- **协议先行、服务可换**。契约在 `docs/remote-config-protocol.md`；两套服务端同一份协议 + 同一份
  `server/admin.html`：`server/workers/worker.js`（KV）、`server/node/server.js`（零依赖 JSON 文件）。
  `server/README.md` 是部署手册。**改协议先改文档，再改三边（两服务 + `RemoteClient` 的 canonical）。**
- 鉴权：bind 时服务端发 32 位 hex token；之后每个请求带
  `X-Device`/`X-Ts`/`X-Sig = HMAC-SHA256(token, METHOD\nPATH\nTS\nBODY)`，服务端校验 5 分钟偏差。
  重绑**轮换 token**（换手机 / 怀疑泄露就走这条）。家庭码（`[A-Z0-9]{4,16}`）既是绑定凭据也是管理台凭据。
- 中间件**不存完整配置**，只存 `bind:/meta:/cmd:/state:`。**一条指令一个键**（v1.1 改的：
  原来 `cfg.cmds` 数组是「读-改-写」，多个子女同时下发必丢一条），ack 直接删键。
  `rev = 秒级时间戳 - 1600000000`（约 1.9e8）——**绝不能把毫秒时间戳当 rev**，
  长辈端 `JSONObject.optInt` 会截断成负数，导致每次同步都重跑历史指令。
  Workers 用 KV（**最终一致**）；Node 用 `store.json` 临时文件 + rename 原子替换。
- 多设备：一个 `code` 一台长辈；`admin.html` 用 `localStorage['elder_devices']` 存多台可切换，
  `?`/`/a/CODE` 打开时自动入列。清单只在子女浏览器里，换浏览器要重加。
  多个子女共用一个 `code` 即可，代价是**无审计、无法单独撤销某人**（要换 code）。
- **幂等兜底**：`RemoteCenter.sync()` 执行前跳过「回执队列里已有的 id」——
  服务端 rev 抖动或 ack 丢包时会重发同一条，靠这个不重复执行。
- 指令白名单 8 条：安全 = `set_font/set_voice/set_alive/set_home_order/push_msg`；
  危险 = `set_sos/set_blocks/set_quick`。**未知 op 一律拒，不下发可执行代码。**
  危险指令进 `remote_pending`，等 `MainActivity.onResume` → `ui/RemotePrompt` 弹窗，老人点同意才
  `RemoteCommand.applyDanger()` 落地；**24 小时不处理自动作废**（`DANGER_TTL_MS`）。
- 落地文件：`util/RemoteClient.kt`（HttpURLConnection + HMAC，`normalizeEndpoint()` 对公网强制 https）、
  `util/RemoteCenter.kt`（deviceId/绑定/同步/回执队列/待确认）、`util/RemoteCommand.kt`（白名单+执行）、
  `ui/RemotePrompt.kt`（本机确认 / 子女留言）、`RemoteActivity.kt` + `activity_remote.xml`（绑定页与诊断）。
  Prefs 新增 `remote_*` 十个键，随 `clearAll()` 一起被恢复出厂清掉。
- 同步时机：**搭「还在吗」那个每小时闹钟**（`AliveAlarmReceiver` 用 `goAsync()` + 后台线程）。
  ⚠️ **`AliveCenter.schedule()` 的开启条件必须是「还在吗」或「已绑定远程」任一成立**，
  关掉「还在吗」时也不能 `cancel()` 而要 `schedule()` 重判——否则绑了远程却没开「还在吗」的用户
  永远不同步（表现为「绑成功了但怎么改都没反应」，极难排查）。
- 回执 `state` 上带 lastActive/battery/fontLevel/alive/pending，管理台因此能显示长辈状态。
- 语义要求：**指令必须幂等**（`set_font` 是「设成某个值」不是「加一级」），重发/重试才安全。
- 密码保护：`RemoteActivity` 与 `AdminActivity` 同级（onCreate + onResume 双校验 `AdminGate.isUnlocked()`），
  入口在高级设置的新卡片 `rowAdminRemote`。
- 若之后要做「短信叫醒」：短信只负责递信封（地址+家庭码）和触发一次立即同步，
  **不能当持续通道**（70 汉字装不下配置）；绑定本身只需做一次，用手输就能省掉 `RECEIVE_SMS`。

## 编码约定 / 坑点
- Adapter 里禁止 `val it = items[position]`（setOnClickListener 的隐式 it 遮蔽）→ 用 `val item`。
- **Adapter 持有外部 list 时 update() 绝不能把 holder 那份原样传回**（内部 clear() 会把外部一起清空）——快捷拨号「重启才生效」真凶。
- 弹窗里不要「落盘后重新反序列化」（HomeBlock 成过期引用，indexOf = -1）。
- 复用 item_home_button.xml 要显式设 layoutParams（原根 match_parent，wrap_content 容器里量不出高度）。
- 跑马灯 + gravity=center 会两头切字：先切 START|CENTER_VERTICAL 再 isSelected，溢出判断在 post{} 里且校验 text 未被复用改掉。
- 色块文字只显示上半截：`minBlockCellHeight()` 双保险 + `fitIcon()` 压图标（下限 26dp，**上限取 R.dimen.icon_normal，不能读 layoutParams.height**，复用会越压越小）。
- AlertDialog 自定义 view 不滚动：自己包 ScrollView，「屏高-260dp」封顶。
- 包名别猜：ShortcutManager 在 android.content.pm；AlarmClock 在 android.provider。「打开时钟」**没有官方 Intent**，`CATEGORY_APP_CLOCK` 不存在（只有 CALENDAR）。
- 透明 Activity：继承 `android.app.Activity`（不能 AppCompatActivity）或 BaseActivity + `@style/Theme.Elder.Translucent`；此时**千万不要配 screenOrientation**（Android 8 抛「Only fullscreen opaque activities can request orientation」）。
- 两个 Gradle 并发会损坏 Kotlin 增量缓存 → `gradle --stop` + 删 `app/build/kotlin`。
- 确认平台常量：`unzip -p <sdk>/platforms/android-34/android.jar android/provider/Settings.class | grep -ao "ACTION_[A-Z_]*DATA[A-Z_]*"`。

## Git
- 远程 `git@github.com:her02008/OlderHelperLauncher.git`，默认分支 `main`。
- **本机必须走 `ssh.github.com:443`**：`github.com:22` 时通时拒。已写进仓库配置
  `core.sshCommand`，直接 `git push` 即可；换机器克隆时要自己加这条。
- `.gitignore` 已排除 build/local.properties/签名文件/`server/node/data/`；
  `.workbuddy/` 只放行 `memory/`。`.gitattributes` = `* text=auto eol=lf`。
