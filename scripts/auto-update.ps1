<#
TrendPilot 自动更新与守护。由计划任务 "TrendPilot-AutoUpdate" 每 10 分钟以普通用户身份运行，不需要管理员。

每次运行：
  1. 守护：服务没在跑就启动；状态接口连续 3 次（约 30 分钟）无响应就重启。
  2. 更新：GitHub main 有新提交时，在源码目录拉取并构建（含全部测试），
     成功后平滑停机、换上新 jar 和文件、启动、健康检查；健康检查失败自动回滚到上一版。
     构建失败则继续跑旧版，同一提交只提醒一次。
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
$LogFile = Join-Path $AppDir 'logs\auto-update.log'

New-Item -ItemType Directory -Force -Path $Bin, (Join-Path $AppDir 'logs') | Out-Null

function Write-Log($msg) {
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

function Read-Text($path) {
    if (Test-Path $path) { return ([string](Get-Content $path -Raw)).Trim() }
    return ''
}

function Send-Telegram($text) {
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
    try {
        Stop-Process -Id $procId -Force
        Start-Sleep -Seconds 3
        return $true
    } catch {
        Write-Log "无法结束进程 PID=${procId}：$($_.Exception.Message)"
        return $false
    }
}

function Report-StopDenied($what) {
    if (Test-Path $Denied) { return }
    Send-Telegram "❗ $what，但旧进程 PID=$(Get-ServicePid) 是管理员权限启动的，自动更新无法结束它。请在任务管理器里结束这个 java.exe，之后会自动接管。"
    Set-Content $Denied (Get-Date)
}

function Invoke-Git([string[]]$GitArgs) {
    $output = & git.exe -C $SrcDir @GitArgs 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "git $($GitArgs -join ' ') 失败：$output" }
    return $output.Trim()
}

# 防止两次运行重叠（构建可能超过 10 分钟）
$mutex = New-Object System.Threading.Mutex($false, 'Local\TrendPilotAutoUpdate')
if (-not $mutex.WaitOne(0)) { exit 0 }

try {
    # ---------- 1. 守护 ----------
    if (-not (Get-ServicePid)) {
        Write-Log '服务未运行，启动中'
        if (-not (Start-TrendPilot)) { Send-Telegram '❗ TrendPilot 启动失败，详见 logs\console.log' }
    } elseif (-not (Test-Healthy)) {
        $n = [int]('0' + (Read-Text $Health)) + 1
        Set-Content $Health "$n"
        Write-Log "状态接口无响应（连续第 $n 次）"
        if ($n -ge 3) {
            if (Stop-TrendPilot) {
                if (Start-TrendPilot) { Send-Telegram '♻️ TrendPilot 无响应约 30 分钟，已自动重启' }
                else { Send-Telegram '❗ TrendPilot 无响应，自动重启后仍未恢复，详见 logs\console.log' }
            } else {
                Report-StopDenied 'TrendPilot 无响应'
            }
        }
    } else {
        Set-Content $Health '0'
        Remove-Item $Denied -ErrorAction SilentlyContinue
    }

    # ---------- 2. 检查新提交 ----------
    if (-not (Test-Path (Join-Path $SrcDir '.git'))) {
        & git.exe clone --quiet --branch $Branch $RepoUrl $SrcDir 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'git clone 失败' }
    }
    Invoke-Git @('fetch', '--quiet', 'origin', $Branch) | Out-Null
    $latest = Invoke-Git @('rev-parse', "origin/$Branch")
    if ($latest -eq (Read-Text $Version) -or $latest -eq (Read-Text $Failed)) { exit 0 }
    $short = $latest.Substring(0, 7)
    $subject = Invoke-Git @('log', '-1', '--format=%s', $latest)
    Write-Log "发现新提交 ${short}：$subject"

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

    # ---------- 4. 平滑停机 → 替换 → 启动 → 健康检查 ----------
    if (-not (Stop-TrendPilot)) {
        Report-StopDenied "新版本 $short 已构建好"
        exit 1
    }
    if (Test-Path $Jar) { Copy-Item $Jar $PrevJar -Force }
    Copy-Item $newJar $Jar -Force
    & robocopy.exe $SrcDir $AppDir /E /NFL /NDL /NJH /NJS /NP /XD .git .mvn data logs target app /XF application-local.yml | Out-Null

    if (Start-TrendPilot) {
        Set-Content $Version $latest
        Remove-Item $Failed, $Denied -ErrorAction SilentlyContinue
        Write-Log "已更新到 $short"
        Send-Telegram "✅ TrendPilot 已自动更新到 $short`n$subject"
    } else {
        Write-Log "新版本 $short 健康检查失败，回滚"
        Set-Content $Failed $latest
        Stop-TrendPilot | Out-Null
        if (Test-Path $PrevJar) { Copy-Item $PrevJar $Jar -Force }
        if (Start-TrendPilot) {
            Send-Telegram "⚠️ TrendPilot 新版本 $short 启动后健康检查失败，已回滚到上一版，运行正常。"
        } else {
            Send-Telegram "❗ TrendPilot 新版本 $short 启动失败，回滚后上一版也没能启动，详见 logs\console.log"
        }
    }
} catch {
    Write-Log "自动更新出错：$($_.Exception.Message)"
} finally {
    $mutex.ReleaseMutex()
}
