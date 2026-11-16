<#
.SYNOPSIS
    Gitee 官方「仓库镜像管理」（Pull 方向）的配置 / 触发 / 校验工具。

.DESCRIPTION
    机制：Gitee 仓库后台配置 Pull 方向镜像（GitHub → Gitee），平时不自动同步；
    需要同步时，用本脚本发一条 API 指令让 Gitee 去 GitHub 拉一次。

    触发接口（实测：GET 会 404，必须 POST）：
        POST https://gitee.com/api/v5/repos/:owner/:repo/remote_mirror/pull?access_token=:Gitee私人令牌

    四个动作：
        Trigger      触发一次同步（需要 Gitee 令牌）
        Status       查镜像配置与最近同步状态（需要 Gitee 令牌）
        Verify       比对 GitHub / Gitee 两端 main 的提交号（不需要令牌）
        CreateMirror 用 API 直接创建 Pull 镜像配置（**不需要绑定 GitHub 账号**）

    CreateMirror 说明：
        Gitee 网页端「添加镜像」要靠 OAuth 绑定 GitHub 账号来填仓库下拉框；
        若 GitHub 账号被 flagged（提示 "This account is flagged, and therefore cannot
        authorize a third party application"），这一步走不通。
        但 Gitee 的建配置路由本身是开放的（POST /api/v5/repos/:owner/:repo/remote_mirror），
        且该端点未收录在公开 OpenAPI 里 ⇒ 参数名靠实测。
        本动作用几组候选参数依次试，打印 Gitee 的原始响应，命中即停。

    令牌：
        Gitee 私人令牌 → 用户头像 → 设置 → 私人令牌 → 勾选 `projects` 权限。
        GitHub 私人令牌（可选，公开仓库免凭据）→ classic PAT，勾 `repo`。
    用法：
        $env:GITEE_TOKEN = 'Gitee令牌'
        $env:GITHUB_TOKEN = 'GitHub令牌'   # 可选
        .\scripts\sync_gitee_mirror.ps1 -Action CreateMirror
        .\scripts\sync_gitee_mirror.ps1                 # 触发一次同步
        .\scripts\sync_gitee_mirror.ps1 -Action Status  # 查镜像配置与最近状态
        .\scripts\sync_gitee_mirror.ps1 -Action Verify  # 比对两端 main 是否一致

    平台侧约束（Gitee 官方口径）：
      · 镜像最短更新间隔 5 分钟；更新请求一旦发出无法中断；
      · 镜像操作会**覆盖**目标仓库的分支、标签和提交记录 ⇒ Gitee 侧只作镜像，别在那边提交；
      · 不支持 Git-LFS；单次同步超 30 分钟视为超时；连续 5 次失败会强制停止。

.PARAMETER Action
    Trigger（默认）= 触发一次同步；Status = 查镜像配置；Verify = 比对两端 main；
    CreateMirror = 用 API 创建 Pull 镜像配置。

.EXAMPLE
    $env:GITEE_TOKEN = 'xxx'; .\scripts\sync_gitee_mirror.ps1
#>
param(
    [ValidateSet('Trigger', 'Status', 'Verify', 'CreateMirror', 'CheckToken', 'Push')]
    [string] $Action = 'Trigger',
    [string] $GiteeOwner = 'hxzhang2000',
    [string] $GiteeRepo = 'NASMusicTV',
    [string] $GitHubOwner = 'hxzhang2000',
    [string] $GitHubRepo = 'NASMusicTV',
    [string] $GitHubToken = $env:GITHUB_TOKEN,
    [string] $Token = $env:GITEE_TOKEN
)

$ErrorActionPreference = 'Stop'

# 本机装了失效的系统级代理残留，显式置空，避免 .NET HTTP 走代理导致连不上 gitee.com
try { [System.Net.WebRequest]::DefaultWebProxy = $null } catch { }

function Get-GiteeApiError($ex) {
    $resp = $ex.Exception.Response
    if ($null -eq $resp) { return "网络层失败：$($ex.Exception.Message)" }
    $code = [int]$resp.StatusCode
    $body = ''
    # PS 会把响应正文放进 ErrorDetails.Message；取不到再退回读流
    if ($ex.ErrorDetails -and $ex.ErrorDetails.Message) {
        $body = [string]$ex.ErrorDetails.Message
    } else {
        try {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $body = $reader.ReadToEnd()
        } catch { }
    }
    $detail = $body
    try {
        $obj = $body | ConvertFrom-Json
        if ($obj.message) { $detail = [string]$obj.message }
        if ($obj.code) { $detail = $detail + ' (code ' + $obj.code + ')' }
    } catch { }
    switch ($code) {
        401 { return "401 令牌无效或权限不足：$detail`n  → 检查 Gitee 私人令牌是否勾选 `projects` 权限、是否已过期（Gitee → 用户头像 → 设置 → 私人令牌）。" }
        403 { return "403 无权限：$detail`n  → 令牌有效但没有该仓库的操作权限；确认令牌属于 $GiteeOwner 这个账号。" }
        404 { return "404 找不到资源：$detail`n  → 仓库路径 `$GiteeOwner/$GiteeRepo` 是否正确；以及该仓库**是否已配置 Pull 方向镜像**（管理 → 仓库镜像管理）。" }
        default {
            if ($detail -match '正在读写') {
                return "仓库正在读写中：$detail`n  → 上一次镜像还没结束，或距上次更新不足 5 分钟（最短间隔 5 分钟）；稍后重试。"
            }
            if ($detail -match '登录失效') {
                return "登录失效：$detail`n  → 令牌无效或缺 `projects` 权限，重新生成 Gitee 私人令牌即可。"
            }
            return "HTTP ${code}：$detail"
        }
    }
}

function Invoke-GiteeApi([string] $Method, [string] $Path) {
    $url = "https://gitee.com/api/v5/repos/$GiteeOwner/$GiteeRepo$Path`?access_token=$([uri]::EscapeDataString($Token))"
    Write-Host ">> $Method https://gitee.com/api/v5/repos/$GiteeOwner/$GiteeRepo$Path"
    try {
        return Invoke-RestMethod -Method $Method -Uri $url -TimeoutSec 60
    } catch {
        throw (Get-GiteeApiError $_)
    }
}

# 不抛异常的原始调用：返回状态码与正文，用于「探测参数名」
function Invoke-GiteeRaw([string] $Method, [string] $Path, [hashtable] $Query) {
    $pairs = @("access_token=$([uri]::EscapeDataString($Token))")
    foreach ($k in $Query.Keys) {
        $v = $Query[$k]
        if ($null -eq $v) { continue }
        $pairs += "$([uri]::EscapeDataString([string]$k))=$([uri]::EscapeDataString([string]$v))"
    }
    $url = "https://gitee.com/api/v5/repos/$GiteeOwner/$GiteeRepo$Path`?$($pairs -join '&')"
    try {
        $r = Invoke-WebRequest -Method $Method -Uri $url -TimeoutSec 60 -UseBasicParsing
        return @{ status = [int]$r.StatusCode; body = [string]$r.Content }
    } catch {
        $status = 0
        $body = ''
        if ($null -ne $_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $body = [string]$_.ErrorDetails.Message }
        return @{ status = $status; body = $body }
    }
}

# 本机到 github.com 的链路会**间歇性被重置**（同一 IP 20.205.243.166：好的时候 0.7 秒，坏的时候 21 秒超时），
# 而 api.github.com 一直正常 ⇒ 这是链路抖动，不是配置问题。网络类命令一律走重试。
function Invoke-GitRetry([string[]] $GitArgs, [int] $Retries = 3, [int] $DelaySeconds = 5) {
    $out = ''
    for ($i = 1; $i -le $Retries; $i++) {
        $out = (& git @GitArgs 2>&1)
        if ($LASTEXITCODE -eq 0) { return @{ ok = $true; out = ($out | Out-String) } }
        if ($i -lt $Retries) {
            Write-Host "   （第 $i 次失败，$DelaySeconds 秒后重试……）"
            Start-Sleep -Seconds $DelaySeconds
        }
    }
    return @{ ok = $false; out = ($out | Out-String) }
}

switch ($Action) {

    'Trigger' {
        if ([string]::IsNullOrWhiteSpace($Token)) {
            throw "缺少 Gitee 私人令牌：先设 `$env:GITEE_TOKEN = '你的令牌'`，或用 -Token 传入（Gitee → 用户头像 → 设置 → 私人令牌 → 勾选 `projects` 权限）。"
        }
        Write-Host ">> 触发 Pull 镜像同步：Gitee 将从 GitHub 拉取 $GitHubOwner/$GitHubRepo"
        try {
            $res = Invoke-GiteeApi -Method 'POST' -Path '/remote_mirror/pull'
            Write-Host "==> 已受理镜像更新请求（最短间隔 5 分钟；请求发出后无法中断）"
            if ($null -ne $res) { $res | ConvertTo-Json -Depth 6 | Write-Host }
            Write-Host "==> 同步结果稍后用 -Action Verify 比对（同步可能耗时数分钟）"
        } catch {
            Write-Host $_.Exception.Message
            exit 1
        }
    }

    'Status' {
        if ([string]::IsNullOrWhiteSpace($Token)) {
            throw "缺少 Gitee 私人令牌：先设 `$env:GITEE_TOKEN = '你的令牌'，或用 -Token 传入。"
        }
        Write-Host ">> 查询镜像配置与最近同步状态"
        try {
            $res = Invoke-GiteeApi -Method 'GET' -Path '/remote_mirror'
            $res | ConvertTo-Json -Depth 8 | Write-Host
            Write-Host "==> 若这里看不到 Pull 方向的镜像，说明后台还没配置（可用 -Action CreateMirror 直接用 API 建）"
        } catch {
            Write-Host $_.Exception.Message
            exit 1
        }
    }

    'Verify' {
        Write-Host ">> 比对两端 main 的提交号（不需要令牌）"
        # 本机 git 全局代理指向死端口（127.0.0.1:31180），必须清空代理走直连
        $gitCommon = @('-c', 'http.proxy=', '-c', 'https.proxy=')

        $r1 = Invoke-GitRetry ($gitCommon + @('ls-remote', "https://github.com/$GitHubOwner/$GitHubRepo.git", 'refs/heads/main'))
        if (-not $r1.ok) { Write-Host "GitHub 端读取失败（链路问题，不是仓库问题）：$($r1.out.Trim())"; exit 1 }
        $r2 = Invoke-GitRetry ($gitCommon + @('ls-remote', "https://gitee.com/$GiteeOwner/$GiteeRepo.git", 'refs/heads/main'))
        if (-not $r2.ok) { Write-Host "Gitee 端读取失败：$($r2.out.Trim())"; exit 1 }

        $ghSha = ($r1.out -split '\s+')[0]
        $gtSha = ($r2.out -split '\s+')[0]
        Write-Host "   GitHub main: $ghSha"
        Write-Host "   Gitee  main: $gtSha"
        if ($ghSha -eq $gtSha) {
            Write-Host "==> 一致：Gitee 镜像已与 GitHub 对齐"
        } else {
            Write-Host "==> 不一致：Gitee 端还没同步到最新"
            Write-Host "    → 执行 .\scripts\sync_gitee_mirror.ps1 触发一次同步，等待几分钟后重跑本检查"
        }
    }

    'CheckToken' {
        if ([string]::IsNullOrWhiteSpace($GitHubToken)) {
            throw "缺少 GitHub 令牌：先设 `$env:GITHUB_TOKEN = '你的令牌'`，或用 -GitHubToken 传入（GitHub → Settings → Developer settings → Personal access tokens → classic，勾 `repo`）。"
        }
        Write-Host ">> 用 GitHub 用户名 + 私人令牌直连仓库（走 HTTP Basic，不涉及 OAuth 授权）"
        # ⚠️ 必须用 Authorization 头，**不要**把凭据拼进 URL：
        #    实测本机到 github.com 的 git 端点，URL 内嵌凭据会被卡死 21 秒后超时，
        #    而同样的凭据走 Authorization 头 0.7 秒就通 ⇒ 拼 URL 会把「网络问题」误报成「令牌无效」。
        $gitCommon = @('-c', 'http.proxy=', '-c', 'https.proxy=')
        $auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${GitHubOwner}:$GitHubToken"))
        $gitAuth = @('-c', 'http.proxy=', '-c', 'https.proxy=', '-c', "http.https://github.com/.extraheader=AUTHORIZATION: basic $auth")
        $r = Invoke-GitRetry ($gitAuth + @('ls-remote', "https://github.com/$GitHubOwner/$GitHubRepo.git", 'refs/heads/main'))
        if (-not $r.ok) {
            $text = $r.out
            Write-Host "==> 令牌不可用：$text"
            if ($text -match 'Authentication failed|403|401') {
                Write-Host "    → 令牌无效、已过期，或缺该仓库的读权限（fine-grained 令牌需勾 Contents: Read 并选中本仓库）"
            } elseif ($text -match 'timed out|Connection|unable to access|Recv failure') {
                Write-Host "    → 这是**网络/链路**问题，不是令牌问题（本机到 github.com 的 git 端点不稳；REST API 正常）"
                Write-Host "    → Gitee 服务器拉 GitHub 不走本机链路，通常不受影响"
            } else {
                Write-Host "    → 401/403：令牌或权限问题；连接超时：链路问题"
            }
            exit 1
        }
        Write-Host "==> 令牌可用：$($r.out.Trim())"
        Write-Host "    → 说明 GitHub 侧凭据没问题，Gitee 端用「用户名 + 令牌」当镜像凭据即可，不必绑定 GitHub 账号"
    }

    'Push' {
        # 推送式同步：**不需要 GitHub 的任何授权**（只用 Gitee 令牌把 GitHub 的内容推到 Gitee）。
        # 适用场景：GitHub 账号被 flagged，Gitee 网页端的 OAuth 绑定配置走不通时的兜底。
        if ([string]::IsNullOrWhiteSpace($Token)) {
            throw "缺少 Gitee 私人令牌：先设 `$env:GITEE_TOKEN = '你的令牌'`，或用 -Token 传入（Gitee → 用户头像 → 设置 → 私人令牌 → 勾选 `projects` 权限）。"
        }
        $repoRoot = Split-Path -Parent $PSScriptRoot
        Set-Location $repoRoot

        $gitCommon = @('-c', 'http.proxy=', '-c', 'https.proxy=')
        # 同样用 Authorization 头带凭据（URL 内嵌凭据在本机会被卡死 21 秒）
        $gitFetch = $gitCommon
        if (-not [string]::IsNullOrWhiteSpace($GitHubToken)) {
            $ghAuth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${GitHubOwner}:$GitHubToken"))
            $gitFetch = @('-c', 'http.proxy=', '-c', 'https.proxy=', '-c', "http.https://github.com/.extraheader=AUTHORIZATION: basic $ghAuth")
        }
        Write-Host ">> 拉取 GitHub 的 $GitHubOwner/$GitHubRepo（main + 标签）"
        $fr = Invoke-GitRetry ($gitFetch + @('fetch', 'origin', 'main', '--tags', '--force'))
        if (-not $fr.ok) {
            $known = (& git rev-parse --verify "origin/main" 2>$null)
            if ([string]::IsNullOrWhiteSpace($known)) {
                Write-Host "==> GitHub 拉取失败，且本地没有 origin/main 可用 —— 先解决网络或远端配置再重试"
                exit 1
            }
            Write-Host "!! GitHub 拉取失败（链路问题）。将使用本地已有的 origin/main：$known"
            Write-Host "   （这是上次成功拉取时的快照，可能不是 GitHub 最新状态；链路恢复后重跑本动作）"
        }

        $base = (& git rev-parse --verify "origin/main").Trim()
        $tagRefs = @(& git tag --merged $base --format='%(refname)' | Where-Object { $_ })
        Write-Host ">> 基准提交（origin/main）: $base"
        Write-Host ">> 可同步标签数: $($tagRefs.Count)"

        $remoteUrl = "https://gitee.com/$GiteeOwner/$GiteeRepo.git"
        if ((& git remote) -contains 'gitee') {
            & git remote set-url gitee $remoteUrl
        } else {
            & git remote add gitee $remoteUrl
        }

        # 令牌走 extraheader，不写进 remote URL
        $auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${GiteeOwner}:$Token"))
        $gitPush = @('-c', 'http.proxy=', '-c', 'https.proxy=', '-c', "http.https://gitee.com/.extraheader=AUTHORIZATION: basic $auth")

        Write-Host ">> 推送分支 main（仅 main，不含任何其它分支）"
        & git @gitPush push gitee "refs/heads/main:refs/heads/main" --force
        if ($LASTEXITCODE -ne 0) { Write-Host "==> 推送分支失败（上面是 git 的原始报错）"; exit 1 }

        if ($tagRefs.Count -gt 0) {
            Write-Host ">> 推送 $($tagRefs.Count) 个标签（只有可从 main 到达的标签）"
            $specs = @()
            foreach ($t in $tagRefs) { $specs += "$($t):$($t)" }
            & git @gitPush push gitee --force @specs
            if ($LASTEXITCODE -ne 0) { Write-Host "==> 推送标签失败（上面是 git 的原始报错）"; exit 1 }
        }

        Write-Host ">> 回读 Gitee 端 main 校验"
        & git @gitPush ls-remote gitee "refs/heads/main"
        if ($LASTEXITCODE -ne 0) { Write-Host "==> 回读校验失败"; exit 1 }
        Write-Host "==> 推送完成：https://gitee.com/$GiteeOwner/$GiteeRepo"
    }

    'CreateMirror' {
        if ([string]::IsNullOrWhiteSpace($Token)) {
            throw "缺少 Gitee 私人令牌：先设 `$env:GITEE_TOKEN = '你的令牌'，或用 -Token 传入（Gitee → 用户头像 → 设置 → 私人令牌 → 勾选 `projects` 权限）。"
        }
        $sourceUrl = "https://github.com/$GitHubOwner/$GitHubRepo.git"
        Write-Host ">> 用 API 直接创建 Pull 方向镜像配置（绕过 Gitee 网页端的 GitHub OAuth 绑定）"
        Write-Host ">> 源仓库：$sourceUrl"
        if ([string]::IsNullOrWhiteSpace($GitHubToken)) {
            Write-Host ">> 未提供 GitHub 令牌（公开仓库通常免凭据）"
        } else {
            Write-Host ">> 使用已提供的 GitHub 令牌作为源仓库凭据"
        }
        Write-Host ">> 该端点未收录在 Gitee 公开 OpenAPI 里，参数名靠逐组试探；命中即停"

        $cred = @{}
        if (-not [string]::IsNullOrWhiteSpace($GitHubToken)) {
            $cred['source_username'] = $GitHubOwner
            $cred['source_password'] = $GitHubToken
        }
        $altCred = @{}
        if (-not [string]::IsNullOrWhiteSpace($GitHubToken)) {
            $altCred['username'] = $GitHubOwner
            $altCred['password'] = $GitHubToken
        }

        $candidates = @(
            @{ label = 'A1: source_url + 凭据 + push_mode=pull + auto_update=false';
               body = (@{ source_url = $sourceUrl; push_mode = 'pull'; auto_update = 'false' } + $cred) },
            @{ label = 'A2: source_url + 凭据 + push_mode=pull';
               body = (@{ source_url = $sourceUrl; push_mode = 'pull' } + $cred) },
            @{ label = 'A3: source_url + 凭据 + auto_update=false';
               body = (@{ source_url = $sourceUrl; auto_update = 'false' } + $cred) },
            @{ label = 'A4: source_url + 凭据 + push_mode=0';
               body = (@{ source_url = $sourceUrl; push_mode = '0' } + $cred) },
            @{ label = 'B: source_url + 凭据';
               body = (@{ source_url = $sourceUrl } + $cred) },
            @{ label = 'C: 仅 source_url（公开仓库免凭据）';
               body = @{ source_url = $sourceUrl } },
            @{ label = 'D: mirror_url/username/password';
               body = (@{ mirror_url = $sourceUrl } + $altCred) },
            @{ label = 'E: remote_mirror[source_url] 嵌套写法';
               body = @{ 'remote_mirror[source_url]' = $sourceUrl } }
        )

        $done = $false
        foreach ($c in $candidates) {
            Write-Host ""
            Write-Host ">> 尝试 [$($c.label)]"
            $r = Invoke-GiteeRaw -Method 'POST' -Path '/remote_mirror' -Query $c.body
            Write-Host "   HTTP $($r.status)"
            if ($r.body) { Write-Host "   $($r.body)" }
            if ($r.status -ge 200 -and $r.status -lt 300) {
                Write-Host "==> 命中：镜像配置创建成功（参数组 $($c.label)）"
                $done = $true
                break
            }
        }

        if (-not $done) {
            Write-Host ""
            Write-Host "==> 所有候选参数组都没被接受。上面的原始报错就是 Gitee 给的线索："
            Write-Host "    · 404 Not Found Project → 路由/仓库路径不对（确认 `$GiteeOwner/$GiteeRepo）"
            Write-Host "    · 400 + 缺参数说明 → 参数名不是上面这几种，把报错原文发我即可继续推"
            Write-Host "    · 401 登录失效 → Gitee 令牌无效或缺 `projects` 权限"
            exit 1
        }

        Write-Host ""
        Write-Host ">> 核对刚创建的配置（务必确认方向是 pull，而不是 push）"
        try {
            $cfg = Invoke-GiteeApi -Method 'GET' -Path '/remote_mirror'
            $cfg | ConvertTo-Json -Depth 8 | Write-Host
        } catch {
            Write-Host $_.Exception.Message
        }
        Write-Host "==> 接下来：.\scripts\sync_gitee_mirror.ps1 -Action Trigger 触发同步，几分钟后 -Action Verify 校验"
    }
}
