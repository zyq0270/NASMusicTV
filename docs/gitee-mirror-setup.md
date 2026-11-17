# Gitee 主仓库发布与校验指引

> ⭐ **所有者裁定（2026-11-17）**：以后**以 Gitee 为主**，**暂时不推 GitHub**。
>
> - 代码与 Release 的**权威源**：`https://gitee.com/hxzhang2000/NASMusicTV`
> - GitHub 仓库视为**冻结的历史归档**：不 `push`、不 `tag`、不 `release`
> - 本地 `main` 是工作副本；发布动作只有一条：**本地 `main` + 可达标签 → Gitee**
> - ⛔ 不要再点 Gitee 网页的「更新导入」/「同步」；那会从 GitHub 拉取**较旧的提交并覆盖 Gitee**

---

## 一、日常流程（唯一推荐路径）

```powershell
# 1) 先看三端状态（只读，不需要令牌）
.\scripts\sync_gitee_mirror.ps1

# 2) 本地提交完成后发布到 Gitee
$env:GITEE_TOKEN = '你的 Gitee 私人令牌'   # 用户头像 → 设置 → 私人令牌 → 勾选 projects 权限
.\scripts\sync_gitee_mirror.ps1 -Action PublishLocal

# 3) 再跑一次只读校验，确认 Gitee 与本地一致
.\scripts\sync_gitee_mirror.ps1
```

`PublishLocal` 的行为：

- 把本地 `HEAD` 推到 `gitee/main`；
- 同时推送所有**可从 `HEAD` 到达的标签**（含 `v2.39.20`）；
- 全程**不访问 GitHub、不改 GitHub 上的任何 ref**；
- 令牌走 `Authorization` 头，不写进 remote URL；
- 失败时立即停止，不会半途继续推标签。

默认 `-Action Verify`（只比对，无副作用）会打印本地 / GitHub / Gitee 三个提交号：

- `Gitee == 本地`、`GitHub` 落后 ⇒ **预期状态**（GitHub 已冻结，不用管）；
- `Gitee != 本地` ⇒ 本地领先就 `PublishLocal`；Gitee 领先就 `git fetch gitee` 后以 Gitee 为准；
- ⛔ 任何情况下都不要用「让 GitHub 覆盖 Gitee」来消掉差异。

---

## 二、发版（Release + APK）

代码推到 Gitee 后，用 `scripts/gitee_release.py` 在同一版本上创建 Release 并上传 APK：

```powershell
$env:GITEE_TOKEN = '你的 Gitee 私人令牌'
python scripts/gitee_release.py                  # 取 app/build.gradle.kts 的 versionName
python scripts/gitee_release.py --dry-run        # 只看将执行的动作
```

脚本从 `CHANGELOG.md` 抽取该版本的小节作为 Release 正文；APK 取 `app/build/outputs/apk/release/NASMusicTV-release-v<版本>.apk`。当前 Gitee 上已有 `v2.39.20` Release（含 APK 与源码归档），**不要**在 GitHub 上补建 Release。

---

## 三、为什么禁止旧的「GitHub → Gitee」镜像

| 事实 | 后果 |
|---|---|
| 2026-11-17 实测 `gitee/main = a0d47c8`（本地 HEAD），`origin/main = f83df51`（落后 6 个提交） | 触发 Pull 镜像会用 `f83df51` **覆盖** Gitee 上的较新代码 |
| Gitee 已有 `v2.39.20` tag + Release + APK；GitHub 无该 tag、Release 数为 0 | 镜像不会同步 Release 附件，覆盖后发行版与代码版本对不上 |
| Gitee 官方口径：镜像操作会覆盖目标仓库的分支、标签和提交记录 | 在 Gitee 上提交的代码会被下一次镜像抹掉 |

因此脚本对以下三个动作加了**硬护栏**，默认直接拒绝执行：

```powershell
.\scripts\sync_gitee_mirror.ps1 -Action Trigger       # 拒绝
.\scripts\sync_gitee_mirror.ps1 -Action CreateMirror  # 拒绝
.\scripts\sync_gitee_mirror.ps1 -Action Push          # 拒绝
```

只有当所有者**明确裁定恢复「GitHub → Gitee」镜像**时，才允许显式加开关：

```powershell
.\scripts\sync_gitee_mirror.ps1 -Action Trigger -AllowLegacyGitHubSync
```

⛔ 不得为了方便而长期带着 `-AllowLegacyGitHubSync` 跑定时任务。

---

## 四、遗留动作（只读诊断，不影响发布）

| 动作 | 作用 | 备注 |
|---|---|---|
| `-Action Status` | 查询 Gitee 后台是否还留着 Pull 镜像配置 | 需要 Gitee 令牌；**看到配置也不要点网页「更新导入」** |
| `-Action CheckToken` | 用 GitHub 用户名 + PAT 验证令牌可用性 | 需要 `GITHUB_TOKEN`；仅诊断，Gitee 发布不需要 GitHub 令牌 |

---

## 五、令牌与权限

- **Gitee 私人令牌**：用户头像 → 设置 → 私人令牌 → 勾选 `projects`。用于 `PublishLocal`、`Status` 和 `gitee_release.py`。
- **GitHub 令牌**（仅遗留诊断）：classic PAT 勾 `repo`；本项目当前不用它发布。

凭据只走请求头，脚本已显式清空本机失效的系统代理残留。手工敲 git 命令时也建议加：

```powershell
git -c http.proxy= -c https.proxy= fetch gitee
```

---

## 六、边界

- **私有分支不在发布范围**：`feat/e51-private` 含不可再分发的上游衍生源码，只存在于本机；⛔ 永不 push / tag / release，也不要用 `git push --mirror` / `--all`。
- **Release 不随代码同步**：`PublishLocal` 只推 `main` + 标签，APK 要由 `gitee_release.py` 单独上传。
- **版本号纪律**：同版本号多次出包必须在交付时给包指纹（字节数 / 修改时间 / SHA256 前 12 位），并提醒先卸载旧版或 `adb install -r`。
- **平台约束**：Gitee 镜像最短更新间隔 5 分钟、不支持 Git-LFS、单次同步超 30 分钟视为超时（仅在使用遗留镜像时相关）。
