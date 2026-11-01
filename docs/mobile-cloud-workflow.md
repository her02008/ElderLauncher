# 手机上改代码：云端沙箱 → GitHub → CI 出包

> 目标：**电脑完全不开**，只用手机上的 WorkBuddy App，改这个项目的代码并推到 GitHub，
> 由 Actions 编译、由 Release 发包装到手机上。

---

## 一、先分清 App 的两种模式

WorkBuddy 移动端（App）输入栏 / 侧边栏顶部可以切换执行环境：

| 模式 | 任务在哪跑 | 要不要电脑 |
|------|-----------|-----------|
| ☁️ **云端工作**（默认） | WorkBuddy 的云端沙箱（一台临时的 Linux 容器） | **不要**，文档原话「彻底摆脱电脑依赖，云端沙箱开即用」 |
| 🖥 连接电脑 | 你自己电脑上的 WorkBuddy | 要，电脑开机 + 客户端运行 + 同一账号 |

**要"完全不开电脑"，就必须用「云端工作」。**
（另一种"助理 / 微信客服号"也是「连接电脑」那一类，电脑得开着，不在本方案范围内。）

---

## 二、能做到什么、做不到什么

| | 云端沙箱 | 说明 |
|---|---|---|
| 读 / 改仓库代码 | ✅ | 容器里有 shell 和文件系统，clone 下来随便改 |
| `git commit` / `git push` | ✅ 需要 token | 沙箱是全新的，没有你的 SSH key，只能用 HTTPS + PAT |
| 跑 `gradle assembleDebug` | ❌ 别试 | 沙箱没装 Android SDK，装一次要下几百 MB，任务几乎肯定超时 |
| 编译验证 | ✅ 交给 CI | push / 开 PR 就会自动编译，比本地跑还稳 |
| 把 APK 装到手机 | ✅ 但不用下载 | 从 Release 的固定链接直接下 |

**核心思路：云端只负责"改代码 + push"，编译和出包全交给 GitHub。**
这条链路本来就已经搭好了（见 `README.md` 编译与发版两节），所以手机端真正的负担很轻。

---

## 三、一次性准备：GitHub 访问令牌（PAT）

仓库是 **public**，所以 `clone` 不需要任何凭据；只有 **push 才需要授权**。

1. 手机浏览器打开 <https://github.com/settings/personal-access-tokens/new>
2. 填：
   - **Token name**：`phone-workbuddy`
   - **Expiration**：7 天（够用，别选无限期）
   - **Repository access** → Only select repositories → `her02008/ElderLauncher`
   - **Permissions**：`Contents: Read and write`；再加 `Actions: Read-only`（这样它能自己查 CI 结果）
3. 生成后复制那串 `github_pat_...`

**安全要求（务必照做）**

- 只给这一个仓库、只给 7 天；用完就去 <https://github.com/settings/tokens?type=beta> 撤销
- 不要写进仓库里的任何文件（`.gitignore` 挡不住你自己手写进去）
- 不要 `git remote set-url` 把 token 拼进 remote（会留在 `.git/config` 和命令历史里）
- 用一次性 URL 的形式推：

```bash
git clone https://github.com/her02008/ElderLauncher.git
cd ElderLauncher
# …改代码…
git add -A && git commit -m "改动说明"
git push https://x-access-token:<PAT>@github.com/her02008/ElderLauncher.git HEAD:refs/heads/feat/xxx
```

> 沙箱日志会记录这条命令，所以 token 一定要短期 + 最小权限。这是取舍：方便 vs 泄露风险，
> 7 天 + 单仓库能把风险压到可接受。

---

## 四、手机上怎么操作（三步）

1. App 顶部模式标签点一下 → 选 **☁️ 云端工作**
2. 输入框里贴下面任意一个模板（把需求换掉），发送
3. 它每要执行一条命令、每改一个文件都会推给你确认，**点同意才动**；途中可以随时追问、随时停

---

## 五、提示词模板（直接复制）

### A. 常规改动

```text
仓库：https://github.com/her02008/ElderLauncher （public，clone 不需要 token）
请用云端沙箱完成：

1. git clone 到工作目录，切一个新分支 feat/<一句话主题>
2. 先读 docs/engineering-notes.md，那是这个项目的硬约束和踩坑清单，必须遵守
3. 需求：<在这里写清楚要改什么，越具体越好；涉及界面就说是哪个页面>
4. 不要尝试编译（沙箱里没有 Android SDK），改完只做静态检查：
   - 逐条核对 engineering-notes.md 第七节的自检清单
   - grep 确认没有把密钥、.workbuddy、build/ 目录带进提交
5. git commit（中文提交信息，说清改了什么、为什么）
6. push 到 feat/<一句话主题>，然后开一个 PR 到 main，把 PR 链接给我

不要直接 push main。
```

### B. CI 编译失败，让它修

```text
仓库：https://github.com/her02008/ElderLauncher
上一个 PR 的 GitHub Actions 编译失败了。

1. 用 GitHub API 查最近一次失败的 run：GET /repos/her02008/ElderLauncher/actions/runs?per_page=5
   拿到失败的 run id 后查 jobs，找到失败步骤的日志
2. 定位报错（Kotlin 编译错误通常在 "e: " 开头的行）
3. clone 仓库、切到同一个分支，修掉它
4. 不要本地编译，改完直接 commit + push 到原分支，让 CI 重跑
5. 等我确认 CI 绿了再合并

如果判断是环境问题（SDK 版本、Gradle 版本）而不是代码问题，先告诉我，别乱改构建配置。
```

### C. 只想先讨论，不想让它动手

```text
仓库：https://github.com/her02008/ElderLauncher
先 clone 下来读代码，不要改任何文件。
我想实现：<需求>
请给出 2~3 种方案，说明各自改哪些文件、风险和影响面，我选完再动手。
```

---

## 六、推荐做法：走分支 + PR，不要直推 main

手机端有个硬伤：**没人替你本地编译，也没人装机验证**。所以：

1. 云端任务一律推到 `feat/...` 分支，开 PR
2. PR 上 CI 会自动编译（`build-apk.yml` 里 `pull_request` 已触发），**绿灯说明至少能编过**
3. 在手机 GitHub 网页上看 diff、看 CI 结果，确认没问题再点 Merge
4. 合并到 main 之后，`latest` 预发布会被自动更新，固定链接就能下到新包

```text
https://github.com/her02008/ElderLauncher/releases/download/latest/elderlauncher-latest-debug.apk
```

这个链接永远是"最新一次 main 构建"，手机上直接下、直接装（签名已固定，能覆盖升级）。

---

## 七、沙箱里的已知坑

| 坑 | 现象 | 对策 |
|---|---|---|
| 沙箱是一次性的 | 新任务里上次 clone 的代码不见了 | 每条指令都从 `git clone` 开始，让它自己拉；仓库很小，几秒钟 |
| 没有 Android SDK | 让它编译 → 卡住 / 报 `SDK location not found` | 指令里明确写「不要编译，交给 CI」 |
| JDK 版本 | 沙箱里可能只有 17 或没有 | 同上，不编译就不用管；真要在沙箱跑 Gradle，记住 **Gradle 8.5 不支持 JDK 25** |
| 没有 `.workbuddy` 记忆 | 它不知道这项目踩过哪些坑 | 所以才有了 `docs/engineering-notes.md`，指令里让它**先读** |
| 网络限制 | `gradle`、`dl.google.com` 之类可能不通 | 只依赖 `github.com` / `api.github.com`，这两个是通的 |
| 会话有时长上限 | 长任务中途断 | 一次只改一件事，拆小；大改分成多个 PR |
| token 会进日志 | 命令被完整记录 | 短期 PAT + 单仓库权限，用完撤销 |

---

## 八、手机上能做的验收（30 秒）

1. PR 的 **Checks** 是绿的（说明能编过，0 error）
2. diff 里没有 `signing/`、`local.properties`、`.workbuddy/`、任何 `*.jks` 之外的奇怪文件
3. `version.properties` 不要手改 —— 版本号由构建任务自动递增，手改会和 tag 对不上
4. 合并后看 Actions 是否 success、`latest` Release 的更新时间是否变了
5. 手机装新包前确认能覆盖安装（签名已固定，理论上不需要卸载）

---

## 九、和「连接电脑」模式怎么选

| 场景 | 用哪个 |
|---|---|
| 电脑不在身边 / 不想开机 / 只想改点代码 | ☁️ 云端工作（本文） |
| 需要真机连 USB 调试、需要看本机日志、需要本机 SDK | 🖥 连接电脑 |
| 需要跑 `assembleDebug` 看告警 | 🖥 连接电脑（或直接看 CI 日志） |

两套可以混用：手机上云端改代码推 PR，回到电脑后再做真机验证。
