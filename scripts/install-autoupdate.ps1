<#
一次性安装（普通用户权限即可，不需要管理员）：注册两个计划任务。
  TrendPilot-Service     运行服务本身（scripts\run-service.cmd），不限运行时长
  TrendPilot-AutoUpdate  每 10 分钟运行 scripts\auto-update.ps1：守护 + 自动更新
重复运行会覆盖旧任务。卸载：Unregister-ScheduledTask TrendPilot-Service,TrendPilot-AutoUpdate
#>
$ErrorActionPreference = 'Stop'
$AppDir = Split-Path -Parent $PSScriptRoot
$user = "$env:USERDOMAIN\$env:USERNAME"
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited

$svcAction = New-ScheduledTaskAction -Execute 'cmd.exe' -Argument "/c `"$AppDir\scripts\run-service.cmd`"" -WorkingDirectory $AppDir
$svcSettings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
$svcTrigger = New-ScheduledTaskTrigger -AtLogOn -User $user
Register-ScheduledTask -TaskName 'TrendPilot-Service' -Action $svcAction -Trigger $svcTrigger -Principal $principal `
    -Settings $svcSettings -Description 'TrendPilot shadow trading service' -Force | Out-Null

$updAction = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$AppDir\scripts\auto-update.ps1`"" `
    -WorkingDirectory $AppDir
$updTrigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 10)
$updSettings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Hours 1) -MultipleInstances IgnoreNew `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
Register-ScheduledTask -TaskName 'TrendPilot-AutoUpdate' -Action $updAction -Trigger $updTrigger -Principal $principal `
    -Settings $updSettings -Description 'TrendPilot watchdog and auto update from GitHub main' -Force | Out-Null

Write-Output 'Installed: TrendPilot-Service, TrendPilot-AutoUpdate'
