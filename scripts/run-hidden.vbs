' Run a TrendPilot script with no visible window (used by the scheduled tasks).
' Usage: wscript.exe //B //Nologo run-hidden.vbs "<full path to .ps1 or .cmd>"
' Waits for the script to finish and returns its exit code, so the task stays running as long as the script does.
Option Explicit
Dim sh, target, cmd, rc
If WScript.Arguments.Count < 1 Then WScript.Quit 2
target = WScript.Arguments(0)
Set sh = CreateObject("WScript.Shell")
If LCase(Right(target, 4)) = ".ps1" Then
    cmd = "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & target & """"
Else
    cmd = "cmd.exe /d /c """"" & target & """"""
End If
rc = sh.Run(cmd, 0, True)
WScript.Quit rc
