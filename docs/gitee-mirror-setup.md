# Gitee 国内镜像配置与同步指引

> 目标：让国内用户克隆 NASMusicTV 走 Gitee（国内访问快），内容与 GitHub 保持一致。
> 机制：Gitee 官方「仓库镜像管理」的 **Pull 方向**（GitHub → Gitee），平时不自动同步，**需要时用一条指令触发**。

---

## 一、前置配置（一次性，网页端操作）

在 Gitee 仓库 `hxzhang2000/NASMusicTV` 后台：

1. 进入仓库 → 顶部「**管理**」→ 找到「**仓库镜像管理**」→ 点「**添加镜像**」。
2. 若弹出提示「未绑定 GitHub 帐号」，按提示先绑定 GitHub 账号（**只支持镜像已绑定 GitHub 帐号授权访问的仓库**）。
3. 「镜像方向」选 **Pull**（GitHub → Gitee；选 Push 是反方向，Gitee → GitHub）。
4. 「镜像仓库」下拉里选 GitHub 上的 `hxzhang2000/NASMusicTV`。
5. 「个人令牌」填 **GitHub 私人令牌**：
   - 进入 GitHub → 用户头像 → Settings → Developer settings → Personal access tokens → Generate new token（classic）；
   - **必须勾选 `repo`**；令牌内容只显示一次，当场存好；
   - 若想改用「自动从 GitHub 同步仓库」（见下条），还需勾选 `admin:repo_hook`。
6. 「自动从 GitHub 同步仓库」——**本方案不勾选**。
   - 勾了 = Gitee 会在 GitHub 侧建 webhook，此后 GitHub 一 push 就自动同步；
   - 不勾 = 只在你手动触发（点「更新」按钮或发那条 API 指令）时才同步，**GitHub 令牌也只要 `repo` 权限**。
7. 保存。

---

## 二、触发同步（一条指令）

**脚本方式（推荐）**：

```powershell
$env:GITEE_TOKEN = '你的Gitee私人令牌'      # Gitee → 用户头像 → 设置 → 私人令牌 → 勾选 projects 权限
.\scripts\sync_gitee_mirror.ps1              # 触发一次同步
```

**等价的一条原始指令**：

```bash
curl -X POST "https://gitee.com/api/v5/repos/hxzhang2000/NASMusicTV/remote_mirror/pull?access_token=$GITEE_TOKEN"
```

> ⚠️ 方法必须是 **POST**。实测 `GET` 同一路径返回 `404 Not Found Project`，`POST` 才进鉴权（无有效令牌时返回 `401 登录失效`）。

**查镜像配置与最近同步状态**：

```powershell
.\scripts\sync_gitee_mirror.ps1 -Action Status
```

**校验两端是否已对齐**（不需要令牌）：

```powershell
.\scripts\sync_gitee_mirror.ps1 -Action Verify
#   GitHub main: f83df51...
#   Gitee  main: f83df51...
#   ==> 一致：Gitee 镜像已与 GitHub 对齐
```

**其余动作**（详见「三点五」）：

| 动作 | 作用 | 需要令牌 |
|---|---|---|
| `-Action CheckToken` | 用 GitHub 用户名 + PAT 直连仓库，验证令牌本身有效（不走 OAuth） | GitHub PAT |
| `-Action CreateMirror` | 用 API 直接建 Pull 镜像配置，绕过 Gitee 网页端的 GitHub 绑定 | Gitee 令牌（+ 可选 GitHub PAT） |
| `-Action Push` | 推送式同步：把 GitHub 的 `main` + 标签直接推到 Gitee，**不需要任何 GitHub 授权** | Gitee 令牌 |

---

## 三、定时自动触发（可选）

把触发指令挂到 Windows 任务计划，每天/每几小时跑一次即可（不需要 GitHub Actions）：

| 任务计划设置项 | 值 |
|---|---|
| 程序 | `powershell.exe` |
| 参数 | `-NoProfile -ExecutionPolicy Bypass -File "D:\hxzhang\MyGithubSoftware\NasAudio\NASMusicTV\scripts\sync_gitee_mirror.ps1"` |
| 触发器 | 每天一次（或每 6 小时） |
| 环境变量 | 先在系统环境变量里放好 `GITEE_TOKEN`（令牌有有效期，过期需换） |

> 定时任务跑的是「触发平台原生镜像」；若账号被 flagged 导致没有镜像配置，把参数换成
> `-Action Push` 即改为推送式同步（同样只需要 `GITEE_TOKEN`）。

---

## 三点五、GitHub 账号被 flagged 时的处置（2026-11-16 实测）

**现象**：在 Gitee 后台「管理 → 仓库镜像管理 → 添加镜像」时，GitHub 弹出
`This account is flagged, and therefore cannot authorize a third party application.`

**定性**：这是 **GitHub 侧**的限制，不是 Gitee 的问题。GitHub 官方文档写明：出现这句提示 = 账号被标记 / 处于审查中，
**重试授权不会解决**，只有 GitHub Support 能解除。它挡的只是「授权第三方应用（OAuth）」这一步：

| 能力 | 被 flag 后 |
|---|---|
| 登录、用 PAT 拉推仓库、跑 GitHub Actions | **照常可用**（本仓 CI 一直在正常发 Release） |
| 用「用户名 + PAT」当 HTTP Basic 凭据访问仓库 | **照常可用** |
| 授权第三方 OAuth 应用（Gitee 的账号绑定） | **被拦** |

⇒ **Gitee 的「镜像配置」完全可以用「GitHub 用户名 + PAT」来做，不必绑定 GitHub 账号。**

**四条路，按推荐顺序**：

| # | 做法 | 需要什么 | 备注 |
|---|---|---|---|
| 0 | **先跑 `-Action Status` 看一眼** | Gitee 令牌 | 该仓库当初就是**按 GitHub URL 导入**的，Gitee 侧很可能**已经存着镜像记录**（仓库页上的「更新导入」按钮就是它）。若 Status 能列出镜像记录 ⇒ 配置已存在，**直接 `-Action Trigger` 就是你要的那条 curl**，完全不需要 OAuth |
| 1 | **仓库页找「更新导入」按钮** | 只要 Gitee 登录态 | 零配置、零 OAuth，点一下即重新拉取 |
| 2 | **API 建配置**：`.\scripts\sync_gitee_mirror.ps1 -Action CreateMirror` | Gitee 令牌（+ 可选 GitHub PAT） | 建镜像配置的路由 `POST /api/v5/repos/:owner/:repo/remote_mirror` 是开放的（实测 401 而非 404），但该端点**未收录在 Gitee 公开 OpenAPI** 里 ⇒ 参数名靠脚本逐组试探，命中即停并打印 Gitee 原始响应 |
| 3 | **推送式同步**：`.\scripts\sync_gitee_mirror.ps1 -Action Push` | Gitee 令牌 | **完全不需要 GitHub 的任何授权**：把 GitHub 的 `main` + 标签直接推到 Gitee。适合先顶上，等 flag 解除后再切回平台原生镜像 |
| 4 | **向 GitHub Support 申诉解除 flag**（治本） | 无 | 见下 |

**先验证 PAT 本身是否可用（不走 OAuth）**：

```powershell
$env:GITHUB_TOKEN = '你的GitHub令牌'
.\scripts\sync_gitee_mirror.ps1 -Action CheckToken
# 成功 => 令牌有效，GitHub 侧凭据没问题，镜像配置可以用「用户名 + 令牌」
```

**申诉工单要素**（入口：<https://support.github.com/contact> → Account → *My account is restricted, suspended, or flagged*）：

- 账号用户名、账号邮箱；
- 报错原文或截图（把上面的英文整句贴上）；
- 首次出现时间；
- 近期异常说明（IP 频繁变化 / 代理或 VPN 切换 / 短时间批量改仓库设置或新建仓库 / 改过邮箱）；
- 明确要求路由到「account restrictions / third-party authorization」团队（表单选项常对不上，在同一工单里写清楚）。

预期 1–3 个工作日。GitHub 社区有同类案例：Support 做了 profile reset 后 OAuth 授权即恢复。

**降低再次被 flag 的概率**：固定出口 IP（本机常挂 Clash/代理，IP 频繁变化是常见触发因素）、开启 2FA、少用脚本批量改账号级设置。

---

## 四、平台侧约束（Gitee 官方口径，触发前先看）

| 约束 | 影响 |
|---|---|
| 最短更新间隔 **5 分钟** | 刚触发完立刻再触发会被拒 |
| 更新请求一旦发出**无法中断** | 触发后只能等它跑完 |
| 镜像操作会**覆盖**目标仓库的分支、标签和提交记录 | Gitee 侧不要提交代码，否则会被下次镜像抹掉 |
| 不支持 **Git-LFS** | 本仓库未使用 LFS，无影响 |
| 单次同步超 **30 分钟**视为超时 | 大仓库慎用；本仓库体量小 |
| 连续 **5 次**失败会强制停止 | 停止后 5 分钟可再发起；失败原因在镜像列表里看 |

---

## 五、故障速查

| 现象 | 原因 | 处理 |
|---|---|---|
| `401 登录失效，无权限访问该资源` | Gitee 令牌无效 / 过期 / 缺 `projects` 权限 | 用户头像 → 设置 → 私人令牌 → 重新生成，勾选 `projects` |
| `404 Not Found Project` | 仓库路径错，或**还没配 Pull 镜像** | 先按第一节配置；确认路径 `hxzhang2000/NASMusicTV` |
| `无权限的私人令牌`（Gitee 侧提示） | 配镜像时填的 GitHub 令牌缺 `repo` 授权 | GitHub 重新生成 classic token，勾 `repo`，回后台更新镜像配置 |
| `仓库正在读写中` | 上一次镜像没结束，或距上次不足 5 分钟 | 等一会儿再触发 |
| 触发成功但 Gitee 没更新 | 同步还在跑 | 过几分钟用 `-Action Verify` 比对提交号 |
| 脚本报 `Failed to connect ... over proxy 127.0.0.1` | 走了本机失效的 git 全局代理 | 脚本已自带 `-c http.proxy= -c https.proxy=` 绕行；若手工敲 git 命令同样要加这两个参数 |
| `Failed to connect to github.com:443 after 21 s` / `Recv failure` / `RPC failed` | **本机到 github.com 的链路被间歇性重置**（DNS 只解析出 `20.205.243.166`，时好时坏；`api.github.com` 一直正常） | **不是令牌问题**。脚本已内置 3 次重试 + `Authorization` 头（凭据拼 URL 会被卡死 21 秒，拼请求头 0.7 秒就通）；**Gitee 服务器拉 GitHub 不走本机链路，不受影响** |

---

## 六、边界（重要）

- **私有分支不在镜像范围**：GitHub 上只有 `main` 一个分支，`feat/e51-private`（E51/E52 上游移植源码）只存在于本机，镜像拉不到它 —— 也因此**绝不要用 `git push --mirror` / `--all` 从本机直推**。
- **镜像只含代码**：GitHub Release 上的 APK 附件**不会**同步到 Gitee，发行版仍以 GitHub 为准。
- **单向**：只做 GitHub → Gitee。反向不要开（Gitee 官方提示双向镜像有丢代码风险）。
- **推送式同步依赖本机链路**：`-Action Push` 要先从本机把 GitHub 内容拉下来；本机到 github.com 链路不稳时，它会退回「上次成功拉取的快照」并明确告警。平台原生镜像由 **Gitee 服务器**去拉 GitHub，不受本机链路影响 ⇒ flag 解除后优先用原生镜像。
