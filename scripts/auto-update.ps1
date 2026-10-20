<#
TrendPilot 自动更新与守护。由计划任务 "TrendPilot-AutoUpdate" 每 3 分钟以普通用户身份运行，不需要管理员。

每次运行：
  1. 确认守护任务 TrendPilot-Watchdog 已注册（服务停止/卡死的检测与拉起由它每 2 分钟负责）。
  2. 更新：GitHub main 有新提交时，在源码目录拉取。
     只改了文档（docs/ 或 .md）：只复制文件，不构建、不重启。
     否则构建（含全部测试），成功后等到 Monitor / Discovery 的空档再平滑停机、换上新 jar 和文件、启动；
     启动后要等到一次成功的 Monitor 才算更新成功，否则自动回滚到上一版（2 小时后再试这个版本）。
     构建失败则继续跑旧版，同一提交只提醒一次。
  另外每天确认一次：接通电源时电脑不睡眠、不休眠（睡眠会让服务停掉）。
  3. 结果通过 Telegram 通知（从本机 config\application-local.yml 读取，不输出其中内容）。

从不改动：config\application-local.yml、data、logs。
服务本身由计划任务 "TrendPilot-Service" 运行 scripts\run-service.cmd。
#>
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$AppDir  = Split-Path -Parent $PSScriptRoot
$SrcDir  = 'D:\SOFT\tools\trendpilot-src'
$RepoUrl = 'https://github.com/leo14881-eng/TrendPilot.git'
$Branch  = 'main'
$Port    = 8080
$Base    = "http://127.0.0.1:$Port"
$Bin     = Join-Path $AppDir 'app'
$Jar     = Join-Path $Bin 'trendpilot.jar'
$PrevJar = Join-Path $Bin 'trendpilot.prev.jar'
$Version = Join-Path $Bin 'version.txt'
$Failed  = Join-Path $Bin 'failed-commit.txt'
$Health  = Join-Path $Bin 'health-failures.txt'
$Denied  = Join-Path $Bin 'stop-denied.txt'
$MonFailed = Join-Path $Bin 'monitor-failed-commit.txt'
$Updating = Join-Path $Bin 'updating.flag'
$LogFile = Join-Path $AppDir 'logs\auto-update.log'

New-Item -ItemType Directory -Force -Path $Bin, (Join-Path $AppDir 'logs') | Out-Null

function Write-Log($msg) {
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

function Read-Text($path) {
    if (Test-Path $path) {
        $raw = Get-Content $path -Raw   # 空文件时是 $null
        if ($null -eq $raw) { return '' }
        return ([string]$raw).Trim()
    }
    return ''
}

function Send-Telegram($text) {
    $text = "【TrendPilot】" + $text
    try {
        $cfg = Join-Path $AppDir 'config\application-local.yml'
        if (-not (Test-Path $cfg)) { return }
        $raw = Get-Content $cfg -Raw -Encoding UTF8
        $token = [regex]::Match($raw, '(?m)^\s*bot-token:\s*["'']?([^"''\s#]+)').Groups[1].Value
        $chat = [regex]::Match($raw, '(?m)^\s*chat-id:\s*["'']?([^"''\s#]+)').Groups[1].Value
        if (-not $token -or -not $chat) { return }
        $body = @{ chat_id = $chat; text = $text } | ConvertTo-Json -Compress
        Invoke-RestMethod -Method Post -Uri "https://api.telegram.org/bot$token/sendMessage" `
            -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 20 | Out-Null
    } catch {
        Write-Log ('Telegram 发送失败：' + ($_.Exception.Message -replace 'bot[0-9]+:[A-Za-z0-9_-]+', 'bot***'))
    }
}

function Test-Healthy {
    try {
        $r = Invoke-WebRequest -Uri "$Base/api/status" -UseBasicParsing -TimeoutSec 15
        return $r.StatusCode -eq 200
    } catch { return $false }
}

function Get-ServicePid {
    $c = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { return [int]$c.OwningProcess }
    return $null
}

# 只结束我们自己的服务：java 进程且命令行含 trendpilot.jar（8080 端口可能被别的程序占用）
function Test-OurProcess($procId) {
    try {
        $p = Get-CimInstance Win32_Process -Filter "ProcessId=$procId" -ErrorAction Stop
        return ($p.Name -match '^javaw?\.exe$') -and ($p.CommandLine -match 'trendpilot\.jar')
    } catch { return $false }
}

function Start-TrendPilot {
    if (-not (Test-Path $Jar)) {
        $built = Join-Path $AppDir 'target\trendpilot.jar'
        if (Test-Path $built) { Copy-Item $built $Jar -Force } else { throw '没有可运行的 jar' }
    }
    & schtasks.exe /Run /TN 'TrendPilot-Service' | Out-Null
    Write-Log '已通过计划任务启动服务'
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 5
        if (Test-Healthy) {
            Set-Content $Health '0'
            Write-Log "服务运行正常 PID=$(Get-ServicePid)"
            return $true
        }
    }
    return $false
}

function Stop-TrendPilot {
    $procId = Get-ServicePid
    if (-not $procId) { return $true }
    try {
        Invoke-WebRequest -Uri "$Base/api/admin/shutdown" -Method Post -Headers @{ 'X-TrendPilot-Admin' = 'shutdown' } `
            -UseBasicParsing -TimeoutSec 10 | Out-Null
        Write-Log "已请求平滑停机 PID=$procId"
    } catch {
        Write-Log '平滑停机请求没有响应，等待后结束进程'
    }
    for ($i = 0; $i -lt 40; $i++) {
        if (-not (Get-Process -Id $procId -ErrorAction SilentlyContinue)) { return $true }
        Start-Sleep -Seconds 3
    }
    if (-not (Test-OurProcess $procId)) {
        Write-Log "端口 $Port 上的进程 PID=$procId 不是 TrendPilot，不结束它"
        return $false
    }
    try {
        Stop-Process -Id $procId -Force
        Start-Sleep -Seconds 3
        return $true
    } catch {
        Write-Log "无法结束进程 PID=${procId}：$($_.Exception.Message)"
        return $false
    }
}

# 等到空档：Monitor 每 5 分钟整点跑（:00 :05 ...），Discovery 在 :17。停在 Monitor 之后 2~3 分钟、避开 :15~:21。
function Wait-QuietWindow {
    for ($i = 0; $i -lt 48; $i++) {
        $m = (Get-Date).Minute
        if ((($m % 5) -eq 2 -or ($m % 5) -eq 3) -and ($m -lt 15 -or $m -gt 21)) { return }
        Start-Sleep -Seconds 10
    }
}

function Get-UtcTime($s) {
    if (-not $s) { return $null }
    try { return [DateTimeOffset]::Parse([string]$s).UtcDateTime } catch { return $null }
}

# 新版本启动后，等到一次成功的 Monitor（最多约 11 分钟，覆盖两次 Monitor）
function Wait-MonitorOk($sinceUtc) {
    for ($i = 0; $i -lt 132; $i++) {
        Start-Sleep -Seconds 5
        try {
            $h = Invoke-RestMethod -Uri "$Base/api/health" -TimeoutSec 15
            $ok = Get-UtcTime $h.lastMonitorOk
            if ($ok -and $ok -ge $sinceUtc) { return $true }
        } catch { }
    }
    return $false
}

function Report-StopDenied($what) {
    if (Test-Path $Denied) { return }
    Send-Telegram "❗ $what，但旧进程 PID=$(Get-ServicePid) 是管理员权限启动的，自动更新无法结束它。请在任务管理器里结束这个 java.exe，之后会自动接管。"
    Set-Content $Denied (Get-Date)
}

# git 输出按 UTF-8 读取。直接用 & git.exe 捕获时，Windows PowerShell 会按系统代码页（中文系统是 GBK）解码，
# 提交说明里的中文会变成乱码。
$GitExe = if ($env:TRENDPILOT_GIT) { $env:TRENDPILOT_GIT } else { 'git.exe' }
function Invoke-Git([string[]]$GitArgs) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $GitExe
    $quoted = @('-C', $SrcDir, '-c', 'i18n.logOutputEncoding=UTF-8', '-c', 'core.quotePath=false') + $GitArgs | ForEach-Object {
        if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
    }
    $psi.Arguments = $quoted -join ' '
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $psi.StandardErrorEncoding = New-Object System.Text.UTF8Encoding($false)
    $p = [System.Diagnostics.Process]::Start($psi)
    $errTask = $p.StandardError.ReadToEndAsync()
    $output = $p.StandardOutput.ReadToEnd()
    $p.WaitForExit()
    if ($p.ExitCode -ne 0) { throw "git $($GitArgs -join ' ') 失败：$output $($errTask.Result)" }
    if ($output.Contains([char]0xFFFD)) { Write-Log "git 输出含无法解码的字符：git $($GitArgs -join ' ')" }
    return $output.Trim()
}

# ---------- 服务器桥：本机通过 SSH 维护云服务器（用户 2026-10-18 同意；密钥在 ssh\，服务器 IP 在 app\server-ip.txt） ----------
# app\server.request 写一个固定动作名（只允许下面几种，不执行任意命令），执行后删除请求文件，输出写 logs\server.log：
#   check      检查服务器能否访问 Binance / Bybit / Telegram / GitHub
#   install    在服务器上安装或重装（从 GitHub 拉取 scripts/linux/install.sh 执行）
#   status     服务器上服务状态、版本、最近日志
#   migrate    迁移到服务器：平滑停掉本机服务并停用，上传本机配置和数据库，启动服务器上的服务
#   migrate-fresh  同上，但只上传配置，服务器用全新的空数据库从头开始（本机数据库原样保留）
#   fallback   切回本机：停用服务器上的服务，启用并启动本机服务
$Disabled = Join-Path $Bin 'disabled.flag'
$BootClone = 'command -v git >/dev/null || { apt-get update -qq && apt-get install -y -qq git >/dev/null; }; rm -rf /tmp/tp-boot && git clone -q --depth 1 https://github.com/leo14881-eng/TrendPilot.git /tmp/tp-boot'
$ServerScripts = @{
    check    = "$BootClone && bash /tmp/tp-boot/scripts/linux/install.sh --check"
    install  = "$BootClone && bash /tmp/tp-boot/scripts/linux/install.sh"
    status   = 'bash /opt/trendpilot/scripts/linux/status.sh'
    migrate  = 'bash /opt/trendpilot/scripts/linux/migrate-in.sh'
    'migrate-fresh' = 'touch /opt/trendpilot/data/trendpilot.db && bash /opt/trendpilot/scripts/linux/migrate-in.sh'
    fallback = 'mkdir -p /opt/trendpilot/app && touch /opt/trendpilot/app/disabled.flag && systemctl stop trendpilot.service && echo "服务器上的服务已停止并停用"'
}

function Write-ServerLog($msg) {
    Add-Content -Path (Join-Path $AppDir 'logs\server.log') -Value ('{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg) -Encoding UTF8
}

# 运行 ssh / scp（最多 50 分钟），标准输出和错误按 UTF-8 追加到 logs\server.log，返回退出码
function Invoke-ServerTool($exe, $argLine, $stdinText) {
    $tmpIn = Join-Path $Bin 'server-in.tmp'; $tmpOut = Join-Path $Bin 'server-out.tmp'; $tmpErr = Join-Path $Bin 'server-err.tmp'
    $p = @{ FilePath = $exe; ArgumentList = $argLine; NoNewWindow = $true; PassThru = $true
        RedirectStandardOutput = $tmpOut; RedirectStandardError = $tmpErr }
    if ($stdinText) {
        [IO.File]::WriteAllText($tmpIn, $stdinText + "`n", (New-Object Text.UTF8Encoding $false))
        $p.RedirectStandardInput = $tmpIn
    }
    $proc = $null
    try { $proc = Start-Process @p } catch { Write-ServerLog "无法启动 ${exe}：$($_.Exception.Message)" }
    if ($null -eq $proc) { Remove-Item $tmpIn, $tmpOut, $tmpErr -ErrorAction SilentlyContinue; return -1 }
    $null = $proc.Handle   # Windows PowerShell 5.1：不先取 Handle，进程退出后 ExitCode 读出来是空的
    if (-not $proc.WaitForExit(50 * 60 * 1000)) { try { $proc.Kill() } catch { }; Write-ServerLog '超过 50 分钟，已中止' }
    foreach ($f in $tmpOut, $tmpErr) {
        if (Test-Path $f) {
            $text = [IO.File]::ReadAllText($f, [Text.Encoding]::UTF8).TrimEnd()
            if ($text) { Add-Content -Path (Join-Path $AppDir 'logs\server.log') -Value $text -Encoding UTF8 }
        }
    }
    Remove-Item $tmpIn, $tmpOut, $tmpErr -ErrorAction SilentlyContinue
    if ($null -eq $proc.ExitCode) { return -1 }
    return $proc.ExitCode
}

function Invoke-ServerRequest {
    $ErrorActionPreference = 'Continue'
    $request = Join-Path $Bin 'server.request'
    if (-not (Test-Path $request)) { return }
    $action = ([string](Read-Text $request)).ToLower()
    Remove-Item $request -Force -ErrorAction SilentlyContinue
    if (-not $action) { Write-ServerLog '请求文件为空，已忽略'; return }
    if (-not $ServerScripts.ContainsKey($action)) { Write-ServerLog "未知动作：$action"; return }
    $ip = Read-Text (Join-Path $Bin 'server-ip.txt')
    if ($ip -notmatch '^\d{1,3}(\.\d{1,3}){3}$') { Write-ServerLog "服务器 IP 无效：$ip"; return }
    $sshDir = Join-Path $AppDir 'ssh'
    $key = Join-Path $sshDir 'id_ed25519'
    $ssh = Join-Path $env:WINDIR 'System32\OpenSSH\ssh.exe'
    $scp = Join-Path $env:WINDIR 'System32\OpenSSH\scp.exe'
    if (-not (Test-Path $key) -or -not (Test-Path $ssh)) { Write-ServerLog '无法执行：密钥或系统自带的 ssh 不存在'; return }
    & icacls.exe $key /inheritance:r /grant:r "$($env:USERNAME):F" 2>&1 | Out-Null   # OpenSSH 要求私钥只有本人可读
    $opts = "-i `"$key`" -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o `"UserKnownHostsFile=$(Join-Path $sshDir 'known_hosts')`" -o ConnectTimeout=20 -o ServerAliveInterval=30"
    Write-ServerLog "== $action"
    if ($action -eq 'migrate' -or $action -eq 'migrate-fresh') {
        # 先平滑停掉本机（数据库写完整），再传配置和数据库（migrate-fresh 只传配置）；任何一步失败都恢复本机运行
        $fresh = $action -eq 'migrate-fresh'
        Set-Content $Disabled (Get-Date).ToString('o')
        if (-not (Stop-TrendPilot)) {
            Remove-Item $Disabled -ErrorAction SilentlyContinue
            Write-ServerLog '本机服务停不下来，取消迁移'
            return
        }
        Write-ServerLog '本机服务已停止并停用'
        $files = @(, @('config\application-local.yml', '/opt/trendpilot/config/application-local.yml'))
        foreach ($n in 'trendpilot.db', 'trendpilot.db-wal', 'trendpilot.db-shm') {
            if (-not $fresh -and (Test-Path (Join-Path $AppDir "data\$n"))) { $files += , @("data\$n", "/opt/trendpilot/data/$n") }
        }
        $ok = (Invoke-ServerTool $ssh "$opts root@$ip bash -s" 'rm -f /opt/trendpilot/data/trendpilot.db*') -eq 0
        foreach ($f in $files) {
            if (-not $ok) { break }
            $code = Invoke-ServerTool $scp "$opts -q `"$(Join-Path $AppDir $f[0])`" root@${ip}:$($f[1])" $null
            Write-ServerLog "上传 $($f[0])：退出码 $code"
            $ok = $code -eq 0
        }
        if ($ok) { $ok = (Invoke-ServerTool $ssh "$opts root@$ip bash -s" $ServerScripts[$action]) -eq 0 }
        if ($ok) {
            Write-ServerLog '迁移完成'
            if ($fresh) {
                Send-Telegram '✅ 已迁移到云服务器运行，账本从零开始重新测试（旧记录保留在电脑上），电脑上的服务已停用（只保留自动更新）'
            } else {
                Send-Telegram '✅ 已迁移到云服务器运行，电脑上的服务已停用（只保留自动更新）'
            }
        } else {
            Remove-Item $Disabled -ErrorAction SilentlyContinue
            if (Start-TrendPilot) { Write-ServerLog '迁移没有完成，本机服务已恢复运行' }
            Send-Telegram '⚠️ 迁移到服务器没有完成，已恢复由电脑运行'
        }
    } elseif ($action -eq 'fallback') {
        Invoke-ServerTool $ssh "$opts root@$ip bash -s" $ServerScripts.fallback | Out-Null
        Remove-Item $Disabled -ErrorAction SilentlyContinue
        if (Start-TrendPilot) { Write-ServerLog '本机服务已启动'; Send-Telegram '✅ 已切回电脑运行' }
    } else {
        $code = Invoke-ServerTool $ssh "$opts root@$ip bash -s" $ServerScripts[$action]
        Write-ServerLog "退出码 $code"
    }
}

# 接电源时不睡眠、不休眠：电脑睡着服务就停了。普通用户可以改当前电源计划；每天检查一次（防止被改回去），失败只提醒一次。
# GitHub Actions 在 Windows 上跑测试的结果（公开仓库，匿名接口，每小时 60 次额度，只在有新提交时才查）。
# 返回 success / failure / pending / unknown（查不到或这个提交没有 CI 记录）。
function Get-CiState($sha) {
    try {
        $m = [regex]::Match($RepoUrl, 'github\.com[/:]([^/]+)/([^/.]+)')
        $api = "https://api.github.com/repos/$($m.Groups[1].Value)/$($m.Groups[2].Value)/commits/$sha/check-runs"
        $r = Invoke-RestMethod -Uri $api -Headers @{ 'User-Agent' = 'TrendPilot-AutoUpdate'; 'Accept' = 'application/vnd.github+json' } -TimeoutSec 20
        $win = @($r.check_runs | Where-Object { $_.name -like '*windows*' })
        if ($win.Count -eq 0) { return 'unknown' }
        if (@($win | Where-Object { $_.status -ne 'completed' }).Count -gt 0) { return 'pending' }
        if (@($win | Where-Object { $_.conclusion -ne 'success' }).Count -gt 0) { return 'failure' }
        return 'success'
    } catch {
        return 'unknown'
    }
}

function Set-NoSleep {
    $ErrorActionPreference = 'Continue'   # powercfg 写 stderr 时不要直接抛出
    $marker = Join-Path $Bin 'nosleep.txt'
    $today = Get-Date -Format 'yyyy-MM-dd'
    $last = Read-Text $marker
    # v3（2026-10-18）：插着电源合盖仍进入了待机（断网、程序被反复冻结）。改为写入所有电源计划（不只当前计划），
    # 并在接电源时关闭待机断网、无人值守睡眠；每天记录一次电源诊断到 logs\power-diag.txt
    if ($last -eq "v3-$today" -or $last -eq "failed-$today") { return }
    $failed = @()
    $schemes = @()
    foreach ($line in (& powercfg.exe /list 2>$null)) {
        if ($line -match '([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})') { $schemes += $Matches[1] }
    }
    if ($schemes.Count -eq 0) { $schemes = @('SCHEME_CURRENT') }
    foreach ($g in $schemes) {
        # 合盖动作：接电源时 0 = 不采取任何操作；用电池时 1 = 睡眠（放进包里不发热耗电）
        & powercfg.exe /setacvalueindex $g SUB_BUTTONS LIDACTION 0 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { $failed += "lid-ac:$g" }
        & powercfg.exe /setdcvalueindex $g SUB_BUTTONS LIDACTION 1 2>&1 | Out-Null
        # 接电源时：空闲不睡眠、不休眠
        & powercfg.exe /setacvalueindex $g SUB_SLEEP STANDBYIDLE 0 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { $failed += "standby-ac:$g" }
        & powercfg.exe /setacvalueindex $g SUB_SLEEP HIBERNATEIDLE 0 2>&1 | Out-Null
        # 以下两项部分电脑没有（隐藏或不支持），失败不算错误：无人值守睡眠超时、待机时保持联网
        & powercfg.exe /setacvalueindex $g SUB_SLEEP UNATTENDSLEEP 0 2>&1 | Out-Null
        & powercfg.exe /setacvalueindex $g SUB_NONE CONNECTIVITYINSTANDBY 1 2>&1 | Out-Null
    }
    foreach ($setting in 'standby-timeout-ac', 'hibernate-timeout-ac') {
        & powercfg.exe /change $setting 0 2>&1 | Out-Null
    }
    & powercfg.exe /setactive SCHEME_CURRENT 2>&1 | Out-Null
    try { Write-PowerDiag } catch { Write-Log "电源诊断记录出错：$($_.Exception.Message)" }
    if ($failed.Count -eq 0) {
        if ($last -notlike 'v3-*') {
            Write-Log "已设置接电源时不睡眠（$($schemes.Count) 个电源计划），合盖不睡眠、待机保持联网"
            Send-Telegram '✅ 已加强防睡眠：所有电源计划都设为接电源时合盖、空闲都不睡眠，待机时保持联网（用电池合盖仍会睡眠）。'
        }
        Set-Content $marker "v3-$today"
    } else {
        Write-Log "设置不睡眠失败：$($failed -join ', ')"
        if ($last -notlike 'failed-*') {
            Send-Telegram "⚠️ 无法关闭电脑自动睡眠（$($failed -join ', ')），电脑睡眠时 TrendPilot 会停止。"
        }
        Set-Content $marker "failed-$today"
    }
}

# 电源诊断：支持的睡眠状态、当前电源计划、合盖设置、最近的睡眠/唤醒/待机事件（排查"插着电合盖仍睡眠"）
function Write-PowerDiag {
    $ErrorActionPreference = 'Continue'   # powercfg 写 stderr 时不要直接抛出
    $out = Join-Path $AppDir 'logs\power-diag.txt'
    $lines = @("==== $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ====")
    $lines += (& powercfg.exe /a 2>&1 | Out-String)
    $lines += (& powercfg.exe /getactivescheme 2>&1 | Out-String)
    $lines += (& powercfg.exe /qh SCHEME_CURRENT SUB_BUTTONS 2>&1 | Out-String)   # /qh 含隐藏项（部分笔记本把"合盖动作"隐藏了）
    $lines += (& powercfg.exe /q SCHEME_CURRENT SUB_SLEEP STANDBYIDLE 2>&1 | Out-String)
    try {
        $ev = Get-WinEvent -FilterHashtable @{ LogName = 'System'; Id = 1, 42, 506, 507, 566 } -MaxEvents 30 -ErrorAction Stop
        foreach ($e in $ev) {
            # 进入新型待机的事件（506）要带上原因（合盖 / 电源键 / 空闲），其余只取第一行
            $msg = if ($e.Id -eq 506) { ($e.Message -replace "`r?`n", ' ') } else { ($e.Message -split "`r?`n")[0] }
            $lines += "$($e.TimeCreated.ToString('yyyy-MM-dd HH:mm:ss')) id=$($e.Id) $($e.ProviderName) $msg"
        }
    } catch {
        $lines += "读取系统事件失败：$($_.Exception.Message)"
    }
    Add-Content -Path $out -Value $lines -Encoding UTF8
}

# 防止两次运行重叠（构建可能超过 3 分钟）
$mutex = New-Object System.Threading.Mutex($false, 'Local\TrendPilotAutoUpdate')
if (-not $mutex.WaitOne(0)) { exit 0 }

try {
    # ---------- 1. 计划任务齐全（守护任务由 install-autoupdate.ps1 注册，缺了就补装） ----------
    # 安装脚本有变化（例如服务改为隐藏窗口运行）或守护任务缺失时重新注册
    $installer = Join-Path $PSScriptRoot 'install-autoupdate.ps1'
    $hash = (Get-FileHash $installer -Algorithm SHA256).Hash
    $hashFile = Join-Path $Bin 'installer.sha256'
    & schtasks.exe /Query /TN 'TrendPilot-Watchdog' 2>&1 | Out-Null
    $wdMissing = $LASTEXITCODE -ne 0
    # 守护任务注册了但超过 15 分钟没跑过（心跳文件没更新）：也重新注册，并提醒
    $wdBeat = Join-Path $Bin 'watchdog-last.txt'
    $wdDead = (Test-Path $wdBeat) -and ((Get-Item $wdBeat).LastWriteTime -lt (Get-Date).AddMinutes(-15))
    if ($wdDead) { Send-Telegram '⚠️ TrendPilot 守护任务超过 15 分钟没有运行，已重新注册' }
    if ($wdMissing -or $wdDead -or $hash -ne (Read-Text $hashFile)) {
        Set-Content $hashFile $hash
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'install-autoupdate.ps1') | Out-Null
        Write-Log '已重新注册计划任务（服务、自动更新、守护）'
    }

    try { Set-NoSleep } catch { Write-Log "设置不睡眠出错：$($_.Exception.Message)" }
    # 远程排查用：app\power-diag.request 存在时立即记录一次电源诊断，然后删掉这个请求文件
    $diagRequest = Join-Path $Bin 'power-diag.request'
    if (Test-Path $diagRequest) {
        try { Write-PowerDiag } catch { Write-Log "电源诊断记录出错：$($_.Exception.Message)" }
        Remove-Item $diagRequest -Force -ErrorAction SilentlyContinue
    }
    # 远程用：app\restart.request 存在时在空档重启一次服务（例如本机配置改了之后），先删请求文件，避免反复重启
    $restartRequest = Join-Path $Bin 'restart.request'
    if ((Test-Path $restartRequest) -and -not (Test-Path $Disabled)) {
        Remove-Item $restartRequest -Force -ErrorAction SilentlyContinue
        Write-Log '收到重启请求，等待空档重启服务'
        Wait-QuietWindow
        if (Stop-TrendPilot) {
            if (Start-TrendPilot) { Write-Log '按请求重启完成' }
            else { Send-Telegram '⚠️ 按请求重启后服务没有正常启动，守护任务会继续尝试' }
        } else {
            Report-StopDenied '按请求重启服务'
        }
    }
    try { Invoke-ServerRequest } catch { Write-Log "服务器请求执行出错：$($_.Exception.Message)" }

    # ---------- 2. 检查新提交 ----------
    if (-not (Test-Path (Join-Path $SrcDir '.git'))) {
        & git.exe clone --quiet --branch $Branch $RepoUrl $SrcDir 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'git clone 失败' }
    }
    Invoke-Git @('fetch', '--quiet', 'origin', $Branch) | Out-Null
    $latest = Invoke-Git @('rev-parse', "origin/$Branch")
    if ($latest -eq (Read-Text $Version) -or $latest -eq (Read-Text $Failed)) { exit 0 }
    # 上次因为 Monitor 没成功而回滚的版本，2 小时后再试（可能只是行情接口临时故障）
    if ($latest -eq (Read-Text $MonFailed) -and (Get-Item $MonFailed).LastWriteTime -gt (Get-Date).AddHours(-2)) { exit 0 }
    $short = $latest.Substring(0, 7)
    $subject = Invoke-Git @('log', '-1', '--format=%s', $latest)
    Write-Log "发现新提交 ${short}：$subject"

    # ---------- 2b. 只改文档：复制文件，不构建、不重启 ----------
    $current = Read-Text $Version
    if ($current -and (Test-Path $Jar)) {
        $changed = @()
        try { $changed = @((Invoke-Git @('diff', '--name-only', $current, $latest)) -split "`r?`n" | Where-Object { $_ }) } catch { $changed = @() }
        $docsOnly = $changed.Count -gt 0 -and -not ($changed | Where-Object { $_ -notmatch '^docs/' -and $_ -notmatch '\.md$' })
        if ($docsOnly) {
            Invoke-Git @('checkout', '--quiet', '--force', $latest) | Out-Null
            & robocopy.exe $SrcDir $AppDir /E /NFL /NDL /NJH /NJS /NP /XD .git .mvn data logs target app /XF application-local.yml | Out-Null
            Set-Content $Version $latest
            Write-Log "只更新文档 $short（未重启）"
            Send-Telegram "📄 TrendPilot 文档已更新到 $short（只改文档，服务没有重启）`n$subject"
            exit 0
        }
    }

    # ---------- 2c. 合并前的 Windows CI 必须通过 ----------
    # 失败：不安装、记下并提醒；还在跑：下次再看；查不到（网络、没有 CI 记录）：照常本机构建，本机测试仍是第二道关。
    $ci = Get-CiState $latest
    if ($ci -eq 'pending') {
        $commitTime = [datetime](Invoke-Git @('log', '-1', '--format=%cI', $latest))
        if ($commitTime -gt (Get-Date).AddMinutes(-40)) { Write-Log "等待 $short 的 Windows CI 结果"; exit 0 }
        Write-Log "$short 的 Windows CI 超过 40 分钟没有结果，改为本机构建测试"
    }
    if ($ci -eq 'failure') {
        Set-Content $Failed $latest
        Write-Log "$short 的 Windows CI 失败，不安装，继续运行旧版"
        Send-Telegram "⚠️ TrendPilot 新版本 $short 在 Windows CI 上测试失败，没有安装，继续运行旧版。`n$subject"
        exit 1
    }

    # ---------- 3. 构建（含全部测试） ----------
    Invoke-Git @('checkout', '--quiet', '--force', $latest) | Out-Null
    $newJar = Join-Path $SrcDir 'target\trendpilot.jar'
    Remove-Item $newJar -ErrorAction SilentlyContinue
    $buildLog = Join-Path $AppDir 'logs\auto-update-build.log'
    & cmd.exe /c "`"$SrcDir\build.cmd`" > `"$buildLog`" 2>&1"
    if (-not (Test-Path $newJar)) {
        Set-Content $Failed $latest
        Write-Log "构建失败 $short，继续运行旧版"
        Send-Telegram "⚠️ TrendPilot 新版本 $short 构建或测试失败，继续运行旧版。`n$subject`n日志：logs\auto-update-build.log"
        exit 1
    }

    # ---------- 4. 等空档 → 平滑停机 → 替换 → 启动 → 等一次成功的 Monitor（期间守护任务不干预） ----------
    # 本机已停用（服务由云服务器运行）：只更新文件，不启动
    if (Test-Path $Disabled) {
        if (Test-Path $Jar) { Copy-Item $Jar $PrevJar -Force }
        Copy-Item $newJar $Jar -Force
        & robocopy.exe $SrcDir $AppDir /E /NFL /NDL /NJH /NJS /NP /XD .git .mvn data logs target app ssh /XF application-local.yml | Out-Null
        Set-Content $Version $latest
        Write-Log "已更新到 $short（本机已停用，未启动服务）"
        exit 0
    }
    # 标记里写本进程 PID：更新程序中途被结束时，守护任务发现这个进程不在了就立刻接手，不用等 30 分钟
    Set-Content $Updating "$PID $((Get-Date).ToString('o'))"
    Wait-QuietWindow
    Set-Content $Updating "$PID $((Get-Date).ToString('o'))"
    if (-not (Stop-TrendPilot)) {
        Report-StopDenied "新版本 $short 已构建好"
        exit 1
    }
    if (Test-Path $Jar) { Copy-Item $Jar $PrevJar -Force }
    Copy-Item $newJar $Jar -Force
    & robocopy.exe $SrcDir $AppDir /E /NFL /NDL /NJH /NJS /NP /XD .git .mvn data logs target app ssh /XF application-local.yml | Out-Null

    $startedUtc = (Get-Date).ToUniversalTime()
    $started = Start-TrendPilot
    $monitorOk = $started -and (Wait-MonitorOk $startedUtc)
    if ($monitorOk) {
        Set-Content $Version $latest
        Remove-Item $Failed, $Denied, $MonFailed -ErrorAction SilentlyContinue
        Write-Log "已更新到 $short"
        # 提交说明全文（策略优化提交里写了改了什么、旧值新值、依据数据、回滚方式），去掉署名行
        $body = (Invoke-Git @('log', '-1', '--format=%B', $latest)) -split "`r?`n" |
            Where-Object { $_ -notmatch '^(Co-Authored-By|Claude-Session):' }
        Send-Telegram ("✅ TrendPilot 已更新到 $short，运行正常`n本次改善：" + (($body -join "`n").Trim()))
    } else {
        $why = if ($started) { '启动后约 11 分钟内没有一次成功的 Monitor' } else { '启动后健康检查失败' }
        Write-Log "新版本 $short ${why}，回滚"
        if ($started) { Set-Content $MonFailed $latest } else { Set-Content $Failed $latest }
        Stop-TrendPilot | Out-Null
        if (Test-Path $PrevJar) { Copy-Item $PrevJar $Jar -Force }
        if (Start-TrendPilot) {
            Send-Telegram "⚠️ TrendPilot 新版本 $short ${why}，已回滚到上一版，运行正常。"
        } else {
            Send-Telegram "❗ TrendPilot 新版本 $short 启动失败，回滚后上一版也没能启动，详见 logs\console.log"
        }
    }
} catch {
    Write-Log "自动更新出错：$($_.Exception.Message)"
} finally {
    Remove-Item $Updating -ErrorAction SilentlyContinue
    $mutex.ReleaseMutex()
}
