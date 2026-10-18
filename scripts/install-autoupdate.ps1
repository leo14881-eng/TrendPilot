<#
一次性安装（普通用户权限即可，不需要管理员）：注册两个计划任务。
  TrendPilot-Service     运行服务本身（scripts\run-service.cmd），不限运行时长
  TrendPilot-AutoUpdate  每 3 分钟运行 scripts\auto-update.ps1：自动更新
  TrendPilot-Watchdog    每 2 分钟运行 scripts\watchdog.ps1：服务停止或卡死时拉起并发 Telegram
重复运行会覆盖旧任务。卸载：Unregister-ScheduledTask TrendPilot-Service,TrendPilot-AutoUpdate,TrendPilot-Watchdog
#>
$ErrorActionPreference = 'Stop'
$AppDir = Split-Path -Parent $PSScriptRoot
$user = "$env:USERDOMAIN\$env:USERNAME"
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited

# 通过 wscript 运行 scripts\run-hidden.vbs 启动脚本：完全不出现窗口（直接用 powershell -WindowStyle Hidden 每次运行仍会闪一下）。
# run-hidden.vbs 不在时（旧版文件）退回原来的 powershell 启动方式。
$Hidden = Join-Path $AppDir 'scripts\run-hidden.vbs'
function New-HiddenAction($script, $fallbackArgs) {
    $target = Join-Path $AppDir "scripts\$script"
    if (Test-Path $Hidden) {
        return New-ScheduledTaskAction -Execute 'wscript.exe' -Argument "//B //Nologo `"$Hidden`" `"$target`"" -WorkingDirectory $AppDir
    }
    return New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $fallbackArgs -WorkingDirectory $AppDir
}

$svcAction = New-HiddenAction 'run-service.cmd' `
    "-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -Command `"& '$AppDir\scripts\run-service.cmd'`""
$svcSettings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
$svcTrigger = New-ScheduledTaskTrigger -AtLogOn -User $user
Register-ScheduledTask -TaskName 'TrendPilot-Service' -Action $svcAction -Trigger $svcTrigger -Principal $principal `
    -Settings $svcSettings -Description 'TrendPilot shadow trading service' -Force | Out-Null

$updAction = New-HiddenAction 'auto-update.ps1' `
    "-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$AppDir\scripts\auto-update.ps1`""
$updTrigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 3)
$updSettings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Hours 1) -MultipleInstances IgnoreNew `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
Register-ScheduledTask -TaskName 'TrendPilot-AutoUpdate' -Action $updAction -Trigger $updTrigger -Principal $principal `
    -Settings $updSettings -Description 'TrendPilot watchdog and auto update from GitHub main' -Force | Out-Null

$wdAction = New-HiddenAction 'watchdog.ps1' `
    "-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$AppDir\scripts\watchdog.ps1`""
$wdTrigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 2)
$wdSettings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Minutes 10) -MultipleInstances IgnoreNew `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
Register-ScheduledTask -TaskName 'TrendPilot-Watchdog' -Action $wdAction -Trigger $wdTrigger -Principal $principal `
    -Settings $wdSettings -Description 'TrendPilot watchdog: restart when stopped or hung, Telegram alerts' -Force | Out-Null

Write-Output 'Installed: TrendPilot-Service, TrendPilot-AutoUpdate, TrendPilot-Watchdog'
