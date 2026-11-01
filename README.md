# 长辈助手 (ElderLauncher)

为老年人定制的 Android 快捷启动器。大色块宫格、大字体、语音播报、一键 SOS，把常用操作集中到一个界面。

## 功能清单

| 功能 | 实现方式 | 备注 |
|------|----------|------|
| 拨打电话 | 打开系统拨号盘 | |
| 通讯录 | 跳转系统通讯录 | |
| 抖音 | 按包名启动（支持抖音/抖音极速版） | 未安装则提示 |
| 微信小程序 | 配置小程序原始ID(gh_xxx@app)可直达，否则打开微信 | 在设置页填写 |
| 清理后台 | 遍历第三方应用调用 killBackgroundProcesses | 需 KILL_BACKGROUND_PROCESSES 权限（已声明） |
| 重启手机 | 无障碍服务弹出电源菜单，长辈点"重启" | 需开启无障碍服务 |
| 手电筒 | Camera2 setTorchMode 切换 | 需相机权限 |
| 无线网 | Android 10+ 弹出系统 WiFi 面板一键切换；旧版跳设置 | |
| 移动流量 | 跳转系统流量设置页 | Android 10+ 普通应用无法直接切换流量 |
| 字体大小 | APP 内 3 档：标准/大/超大，通过 fontScale 缩放 | 改后界面自动重建 |
| SOS 紧急呼叫 | 一键发送定位短信 + 拨号给预设联系人 | 需在设置页配置紧急联系人 |
| 语音播报 | 每次点击用 TTS 朗读功能名 | 可在设置页关闭 |
| 常用联系人快捷拨号 | 首页底部头像，点击拨号，长按删除 | 从通讯录选取 |
| 防误触 | 重启/清理后台弹确认对话框 | |
| 时间问候 | 首页顶部大字时钟 + 问候语 | 天气可自行接入 API |

## 技术栈

- 原生 Android + Kotlin
- minSdk 24 (Android 7.0) / targetSdk 34
- ViewBinding、Material Components、RecyclerView、CardView
- 无障碍服务（清理后台/电源菜单）、设备管理员

## 编译运行

1. 用 Android Studio (Hedgehog 或更高) 打开 `ElderLauncher` 目录，或命令行：
   ```bash
   cd ElderLauncher
   ./gradlew assembleDebug
   ```
   产物：`app/build/outputs/apk/debug/app-debug.apk`
2. 安装到老人手机：`adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. 首次打开会请求电话、通讯录、短信、定位、相机权限，请全部允许。
4. 进入「设置 → 权限与辅助功能」：
   - 开启**无障碍服务**（用于"重启手机"弹出电源菜单、"清理后台"）。
   - 开启**设备管理员**（可选，用于锁屏相关能力）。
5. 在「设置 → 紧急联系人」选择一位家人作为 SOS 接收人。
6. 回首页即可使用。底部"点击添加家人快捷拨号"可添加常用联系人头像。

## 关于"重启/清理/流量"的说明

Android 出于安全考虑，普通应用（未 root、非系统签名）无法直接重启手机或切换移动数据。本应用采用免 root 方案：

- **重启**：通过无障碍服务的 `GLOBAL_ACTION_POWER_DIALOG` 弹出系统电源菜单，长辈再点"重启"。部分机型电源菜单只有"关机"，则只能关机后手动开机。
- **清理后台**：遍历已安装第三方应用调用 `killBackgroundProcesses`，释放后台缓存进程（不会强制停止前台应用）。效果不如系统"一键加速"彻底，但安全无风险。
- **移动流量**：跳转系统流量设置页，长辈再点一下开关。Android 13+ 会尝试弹出网络连接面板。

如需更彻底的清理/直接重启，需要老人手机 root，或刷入系统签名版本。

## 可扩展点

- 天气：`MainActivity.updateGreeting()` 处接入和风/心知天气 API，替换问候语。
- 开机自启：`BootReceiver` 已配置，安装后开机自动进入应用。若不希望自启，在系统设置中禁用其开机启动权限。
- 设为默认桌面：在 `AndroidManifest.xml` 给 MainActivity 添加 `HOME` category 即可作为老人桌面。

## 目录结构

```
ElderLauncher/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/elder/launcher/
│   │   ├── ElderApp.kt              # Application，初始化 TTS
│   │   ├── BaseActivity.kt          # 字体缩放基类
│   │   ├── MainActivity.kt          # 主界面（9宫格+快捷联系人）
│   │   ├── SettingsActivity.kt      # 设置页
│   │   ├── SosActivity.kt           # 紧急求助
│   │   ├── ContactsPickerActivity.kt# 通讯录选取中转
│   │   ├── ui/                      # 适配器 + HomeAction 枚举
│   │   ├── service/ElderAccessibilityService.kt
│   │   ├── receiver/                # 设备管理员 + 开机
│   │   └── util/                    # Prefs/Font/Tts/Torch/Action/Permission
│   └── res/                         # 布局、drawable 图标、values、xml 配置
└── build.gradle.kts
```

## 安全声明

无障碍服务仅在用户主动触发"重启/清理"时执行全局动作，不读取、不上传任何屏幕内容。所有数据（联系人、设置）仅存于本地 SharedPreferences。
