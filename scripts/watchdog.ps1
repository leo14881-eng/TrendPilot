<#
TrendPilot 守护。由计划任务 "TrendPilot-Watchdog" 每 2 分钟以普通用户身份运行，独立于服务本身。

  - 服务没在跑（被关或自己挂了）：Telegram 告知"服务停止"，立即拉起，恢复后告知"已恢复"。
  - 服务卡死（进程在但 /api/health 连续 3 次无响应，或 Monitor 超过 20 分钟没跑）：结束进程并拉起。
  - 连续 3 次拉起失败：停止重试 1 小时并 Telegram 告警，1 小时后再试。
  - 最近 3 次 Monitor 全部失败（进程正常，多半是行情接口问题）：只提醒一次，不重启。
  - 自动更新正在换版本时（app\updating.flag 不超过 30 分钟）不干预。
#>
$ErrorActionPreference = 'Stop'
$script:MonitorFailing = $false
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$AppDir  = Split-Path -Parent $PSScriptRoot
$Port    = 8080
$Base    = "http://127.0.0.1:$Port"
$Bin     = Join-Path $AppDir 'app'
$State   = Join-Path $Bin 'watchdog-state.json'
$Updating = Join-Path $Bin 'updating.flag'
$LogFile = Join-Path $AppDir 'logs\watchdog.log'

New-Item -ItemType Directory -Force -Path $Bin, (Join-Path $AppDir 'logs') | Out-Null

function Write-Log($msg) {
    Add-Content -Path $LogFile -Value ('{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg) -Encoding UTF8
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

function Get-State {
    $s = @{ down = $false; downSince = ''; hangCount = 0; startFailures = 0; pausedUntil = ''; monitorAlerted = $false }
    if (Test-Path $State) {
        try {
            $j = Get-Content $State -Raw | ConvertFrom-Json
            foreach ($k in @($s.Keys)) { if ($null -ne $j.$k) { $s[$k] = $j.$k } }
        } catch { }
    }
    return $s
}

function Save-State($s) { $s | ConvertTo-Json | Set-Content $State -Encoding UTF8 }

function Get-ServicePid {
    $c = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { return [int]$c.OwningProcess }
    return $null
}

# 返回 'ok' / 'stale'（任务长时间没跑）/ 'noreply'
function Get-Health {
    try {
        $h = Invoke-RestMethod -Uri "$Base/api/health" -TimeoutSec 20
        $script:MonitorFailing = [bool]$h.monitorFailing
        if ($h.ok) { return 'ok' }
        return 'stale'
    } catch {
        try {
            # 旧版本没有 /api/health：状态接口能返回也算正常
            $r = Invoke-WebRequest -Uri "$Base/api/status" -UseBasicParsing -TimeoutSec 20
            if ($r.StatusCode -eq 200) { return 'ok' }
        } catch { }
        return 'noreply'
    }
}

function Start-TrendPilot {
    & schtasks.exe /Run /TN 'TrendPilot-Service' | Out-Null
    for ($i = 0; $i -lt 24; $i++) {
        Start-Sleep -Seconds 5
        if ((Get-ServicePid) -and (Get-Health) -ne 'noreply') { return $true }
    }
    return $false
}

$mutex = New-Object System.Threading.Mutex($false, 'Local\TrendPilotWatchdog')
if (-not $mutex.WaitOne(0)) { exit 0 }
try {
    if ((Test-Path $Updating) -and ((Get-Item $Updating).LastWriteTime -gt (Get-Date).AddMinutes(-30))) { exit 0 }
    $s = Get-State
    if ($s.pausedUntil -and (Get-Date) -lt [datetime]$s.pausedUntil) { exit 0 }

    $procId = Get-ServicePid
    $reason = $null
    if (-not $procId) {
        $reason = '服务没有在运行（被关闭或自己退出）'
    } else {
        $h = Get-Health
        if ($h -eq 'ok') {
            if ($s.down) {
                $mins = [int]((Get-Date) - [datetime]$s.downSince).TotalMinutes
                Send-Telegram "✅ TrendPilot 已恢复运行（PID=$procId，中断约 $mins 分钟）"
                Write-Log "已恢复 PID=$procId"
            }
            $s.down = $false; $s.hangCount = 0; $s.startFailures = 0; $s.pausedUntil = ''
            if ($script:MonitorFailing -and -not $s.monitorAlerted) {
                Send-Telegram '⚠️ TrendPilot 最近 3 次 Monitor 全部失败（服务进程正常，多半是交易所行情接口出错）。持仓暂时没有被检查，恢复后会再通知。'
                Write-Log 'Monitor 连续 3 次失败'
                $s.monitorAlerted = $true
            } elseif (-not $script:MonitorFailing -and $s.monitorAlerted) {
                Send-Telegram '✅ TrendPilot Monitor 已恢复正常'
                $s.monitorAlerted = $false
            }
            Save-State $s
            exit 0
        }
        $s.hangCount = [int]$s.hangCount + 1
        Write-Log "健康检查异常：$h（连续第 $($s.hangCount) 次）PID=$procId"
        if ($h -eq 'stale' -or $s.hangCount -ge 3) {
            $reason = if ($h -eq 'stale') { '服务卡住了（Monitor 超过 20 分钟没有运行）' } else { '服务没有响应（约 6 分钟）' }
            try { Stop-Process -Id $procId -Force } catch { Write-Log "结束进程失败：$($_.Exception.Message)" }
            Start-Sleep -Seconds 3
        } else {
            Save-State $s
            exit 0
        }
    }

    if (-not $s.down) {
        $s.down = $true
        $s.downSince = (Get-Date).ToString('o')
        Send-Telegram "⚠️ TrendPilot 停止：$reason，正在自动重启"
    }
    Write-Log "重启：$reason"
    $s.hangCount = 0
    if (Start-TrendPilot) {
        $mins = [int]((Get-Date) - [datetime]$s.downSince).TotalMinutes
        Send-Telegram "✅ TrendPilot 已恢复运行（PID=$(Get-ServicePid)，中断约 $mins 分钟）"
        Write-Log "已恢复 PID=$(Get-ServicePid)"
        $s.down = $false; $s.startFailures = 0
    } else {
        $s.startFailures = [int]$s.startFailures + 1
        Write-Log "启动失败（连续第 $($s.startFailures) 次）"
        if ($s.startFailures -ge 3) {
            $s.pausedUntil = (Get-Date).AddHours(1).ToString('o')
            $s.startFailures = 0
            Send-Telegram '❗ TrendPilot 连续 3 次自动启动失败，暂停重试 1 小时后再试。请查看 logs\console.log'
        }
    }
    Save-State $s
} catch {
    Write-Log "守护出错：$($_.Exception.Message)"
} finally {
    $mutex.ReleaseMutex()
}
