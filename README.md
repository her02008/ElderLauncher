# 长辈助手 · ElderLauncher

![Build APK](https://github.com/her02008/ElderLauncher/actions/workflows/build-apk.yml/badge.svg)

给老人用的 Android 桌面（Launcher）。把「打电话、找儿子、看微信、开抖音、喊救命」这类每天都要做的事，
压缩成一屏大色块；剩下的全交给家人——远程改设置、跌倒自动报警、没接的电话回家再提醒。

- 包名 `com.elder.launcher`，minSdk 24（Android 7.0）/ targetSdk 34
- 纯原生 Kotlin + ViewBinding，无 Compose、无第三方 UI 库、无拼音库
- 依赖只有 AndroidX + Material，APK 体积小、冷启动快

---

## 一、长辈看到的样子

首屏是 9 宫格大色块（超过 9 个时第 9 格变成「更多」），下面按顺序排三块区域：

| 区域 | 内容 |
|------|------|
| SOS 求助 | 一键拨给紧急联系人并发定位短信 |
| 功能色块 | 电话、通讯录、微信、抖音、视频号、手电筒、WiFi、流量、清理、重启、语音…… 可自由增删、排序、换色 |
| 快捷拨号 | 最多 12 个亲人头像，点头像弹「打电话 / 发短信」两个大按钮 |

顶部只有时间和日期（`11月1日 星期日`），点时间进闹钟、点日期进日历。没有问候语、没有广告、没有推荐位。

**设计上的几个刻意选择**

- 所有按钮 ≥ 84dp，字不随系统字号变化而塌陷（APP 内三档：标准 / 大 / 超大）
- 点头像是「弹大按钮」而不是直接拨出，滑完列表 350ms 内不响应点击 —— 都为了防手抖误触
- 数字键、头像、色块点击都会 TTS 念出名字，眼睛不好也能确认自己点对了
- 能不给的权限就不给：不读短信、不要 `READ_CALL_LOG`（见第五节）

## 二、功能一览

### 常用操作
- **大键盘拨号**：自带拨号盘（不跳系统），数字键有语音播报，左下 1/3 是通讯录、右下 2/3 是拨出
- **微信小程序直达**：配置过的小程序一键直开，可配多个；没配就打开微信
- **视频号**：粘贴分享链接即可一键进视频号。微信没有可用的 scheme，靠无障碍自动点「发现 → 视频号」，
  失败会退回到「打开微信 + 提示」
- **桌面快捷方式**：别的 App「添加到桌面」的快捷方式能落到这里（自己实现了 `CONFIRM_PIN_SHORTCUT`）
- **手机桌面**：注册了 HOME，可以作为默认桌面整体替换掉系统桌面

### 家人关心的
- **未接来电提醒**：靠通知使用权识别，回桌面时弹出「谁打过」+ 一键回拨。同号合并、24 小时过期
- **「恰了么？」**：24 小时没动手机就给家人发一条短信，家人可回拨、可暂停 1/3/7 天
- **跌倒检测**：加速度计识别"自由落体 + 撞击"，60 秒没被捡起就响铃震动、弹全屏提醒；
  3 分钟无回应则发短信（带当前电量）+ 直接拨给家人。家人回拨**自动接听**让家人听现场，
  通话确认情况后由家人挂断即退出报警。响铃强度按电量自动降档，避免把电放光
- **远程设置**：长辈手机绑到家人自己的服务端（Cloudflare Workers 或家里 NAS 二选一），
  家人在网页上改字号、语音开关、首页顺序、推留言；改色块 / 改 SOS / 改快捷拨号属于危险操作，
  会在长辈手机上弹窗等本人点「同意」才生效，24 小时不处理自动作废

### 管理
- **两级设置**：普通设置（字号、板块顺序、快捷拨号、紧急联系人、语音开关）不需要密码；
  **高级设置**（功能块、桌面、无障碍、设备管理员、未接来电、恰了么、远程、跌倒、改密码）
  统一加 6 位密码门，防止长辈自己点进去把桌面弄乱
- **忘记密码**：拨号盘输入 `*#0454#*` 即可重置（可勾选「连数据一起恢复出厂」，会二次确认）

## 三、编译

### 用 GitHub Actions（推荐，不装环境）

仓库自带 [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)：

1. 打开仓库 → **Actions** → 左侧 `Build APK` → 右侧 **Run workflow**
2. 等 2~4 分钟，进这次运行页面最下方 **Artifacts** 下载 `elderlauncher-debug-vX.Y.Z`
3. 解压得到 `elderlauncher-vX.Y.Z-debug.apk`，传到手机安装

push 到 `main` 也会自动跑一次，产物保留 30 天。

### 本地编译

需要 **JDK 17 或 21**（Gradle 8.5 不认 JDK 25，注意 Android Studio 自带的 JBR 可能就是 25）+ Android SDK 34。

```bash
# 方式一：Android Studio 打开目录直接 Run

# 方式二：命令行（项目没有提交 gradle-wrapper.jar，用本机 Gradle 8.5）
export JAVA_HOME=/path/to/jdk-17
gradle assembleDebug --console=plain

# 产物：app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次用 Android Studio 打开会生成 `local.properties`（写 `sdk.dir`），这个文件不入库；
命令行编译则走 `ANDROID_HOME` 环境变量。

### 版本号

`version.properties` 是版本号的唯一来源。**每次执行打包类任务时 `versionCode` 与 `versionName`
末位自动 +1 并写回文件**（`gradle tasks`、`clean` 这类任务不动它）。设置页「关于」显示的版本
就是从这里读进 `BuildConfig` 的。想跳号直接改文件。

### Release 包

`assembleRelease` 没配签名，产出的是 unsigned APK（不能直接安装）。要分发就自己加
`signingConfigs`——`keystore.properties` 已在 `.gitignore` 里，别把密钥提上来。

## 四、服务端（远程设置）

服务端不是必需的，**不绑远程就是个纯粹的本地桌面**。要绑的话二选一，两边跑同一份协议、
同一个管理页面：

```bash
# A. Cloudflare Workers（免费，外网可访问）
cd server/workers && npx wrangler deploy

# B. 家里 NAS / 小主机（零依赖 Node，配合 cloudflared 或内网穿透）
cd server/node && node server.js
```

部署细节、环境变量、家庭码怎么用见 [`server/README.md`](server/README.md)；
协议契约（鉴权、指令白名单、存储模型、多子女共用）见
[`docs/remote-config-protocol.md`](docs/remote-config-protocol.md)。

**安全上的几个约定**

- 每个请求带 `X-Device` / `X-Ts` / `X-Sig`，签名是 `HMAC-SHA256(token, METHOD\nPATH\nTS\nBODY)`，
  服务端校验 5 分钟时间偏差、常量时间比较
- 家庭码既是绑定凭据也是管理台凭据；绑定后可轮换 token（换手机 / 怀疑泄露走这条）
- 指令走**白名单**，未知 op 一律拒绝，任何时候都不下发可执行代码
- 服务端只存 `bind / meta / cmd / state`，不存完整配置
- APP 侧对公网地址**强制 https**；明文 http 只放行 localhost 与 10./192.168./172.16-31. 内网段

## 五、权限说明

| 权限 | 用来干什么 | 不给会怎样 |
|------|-----------|-----------|
| `CALL_PHONE` | 快捷拨号直接拨出 | 退化成跳系统拨号盘 |
| `READ_CONTACTS` / `WRITE_CONTACTS` | 选联系人、紧急联系人 | 只能手输号码 |
| `SEND_SMS` | SOS 定位短信、「恰了么」与跌倒预警 | 只能响铃，发不出短信 |
| `ACCESS_FINE_LOCATION` | SOS 短信里带位置 | 短信里没有地址 |
| `ANSWER_PHONE_CALLS` | 跌倒时自动接听家人回拨（Android 8+） | 退化成模拟耳机键，部分机型无效 |
| `RECORD_AUDIO` | 语音命令 | 语音色块不可用 |
| `CAMERA` | 手电筒 | 手电筒不可用 |
| `KILL_BACKGROUND_PROCESSES` | 清理后台 | 只清本应用 |
| `QUERY_ALL_PACKAGES` | 列出已装应用供选择 | 「其他应用」选不到 |
| `SCHEDULE_EXACT_ALARM` | 「恰了么」每小时检查、跌倒超时 | 检查被系统推迟，报警变慢 |
| `POST_NOTIFICATIONS` | 未接来电 / 报警通知 | 通知不显示 |
| 无障碍服务 | 视频号自动点、清理后台、电源菜单 | 视频号只能开微信首页 |
| 通知使用权 | 识别未接来电 | 完全收不到未接来电 |
| 设备管理员 | 防误删、锁定 | 可被绕过 |

**不做的事**：不读短信内容、不要 `READ_CALL_LOG`（Android 10 起非默认电话应用本来就拿不到）、
不上传通讯录、没有任何第三方统计 SDK。远程设置的数据只存在家人自己的服务器上。

## 六、已知限制（写在这里，省得以为是 bug）

- **跌倒检测**只识别硬地面上的自由落体 + 撞击。床、沙发这类软面可能测不到；手机在口袋里
  晃得厉害时也不准。阈值需要按真机调，进程被杀后检测即停止。
- **自动接听**各 ROM 差异很大，部分定制系统会拦。
- **视频号**依赖无障碍点击，微信一改版就得跟着改。微信对外链本来就不配合
  （`channels.weixin.qq.com` 只在客户端内有效）。
- **远程设置最长 1 小时生效**：同步搭在「恰了么」的每小时闹钟上，不是长连接。
- **鸿蒙 4** 的 `RoleManager` HOME 弹窗会静默失败，所以走系统「默认应用」设置页；纯净模式会拦侧载。
- **未接来电**靠解析通知文案，各家 ROM 写法不同，高级设置里有诊断入口可看原始通知。

## 七、代码结构

```
app/src/main/java/com/elder/launcher/
├── MainActivity.kt            桌面主界面（时间、SOS、色块、快捷拨号、区域排序）
├── SettingsActivity.kt        普通设置（免密码）
├── AdminActivity.kt           高级设置（密码门）
├── PasswordActivity.kt        设置 / 确认 / 校验密码
├── HomeBlocksActivity.kt      功能块管理（多选、↑↓、长按拖动）
├── HomeOrderActivity.kt       三块区域的上下顺序
├── QuickDialsActivity.kt      快捷拨号管理
├── DialActivity.kt            大键盘拨号
├── FallActivity.kt            跌倒全屏提醒
├── RemoteActivity.kt          远程绑定与诊断
├── VoiceActivity.kt           全屏语音
├── ui/                        色块、菜单、列表、弹窗、远程确认
├── util/                      色块模型、执行器、密码门、TTS、语音识别、
│                              跌倒检测、远程同步、未接来电、图标存储…
├── service/                   无障碍服务、通知监听服务
└── receiver/                  开机自启、每小时检查、设备管理员
server/
├── workers/                   Cloudflare Workers 版（KV）
├── node/                      Node 版（零依赖，JSON 文件 + 原子替换）
└── admin.html                 两版共用的管理页面
docs/                          协议与设计文档
version.properties             版本号唯一来源
```

## 八、文档

| 文档 | 内容 |
|------|------|
| [`docs/remote-config-protocol.md`](docs/remote-config-protocol.md) | 远程设置的协议契约，改协议先改它 |
| [`docs/design-voice-alive.md`](docs/design-voice-alive.md) | 语音命令的拼音匹配与「恰了么」状态机 |
| [`server/README.md`](server/README.md) | 服务端部署手册 |

## 九、许可

个人自用项目，未附开源协议。要拿去改可以直接拿，但请自行承担风险——这类应用会动到拨号、
短信、无障碍，装之前先在旧手机上试。

---

**给家属的一句话**：装好后请先做三件事 ——
① 在设置里配好紧急联系人和「恰了么」联系人；
② 进高级设置把无障碍、通知使用权都打开（不然视频号、未接来电都不工作）；
③ 把「长辈助手」设成默认桌面，并在电池设置里给它「允许后台运行」，否则定时检查会被系统杀掉。
