# Runs the IW4L guest against tools/fake_mw2_host.py (no Skyrim, no Minecraft) and saves the
# overlay it publishes to a PNG.
#
#   powershell -ExecutionPolicy Bypass -File tools\test_mw2_guest.ps1 -Iw4l <iw4l.exe> [-Mw2 <MW2 folder>] [-Out <png>]
param(
    [Parameter(Mandatory = $true)][string]$Iw4l,
    [string]$Mw2 = "C:\Steam\steamapps\common\Call of Duty Modern Warfare 2",
    [string]$Out = "$PSScriptRoot\..\mw2_overlay.png",
    [int]$Width = 1280,
    [int]$Height = 720,
    [int]$Seconds = 90,
    [string]$Cmds = "wait world; spawn 0; force_match_start"
)
$ErrorActionPreference = "Stop"
$hostProc = Start-Process python -ArgumentList @("$PSScriptRoot\fake_mw2_host.py", "$Seconds", "`"$Out`"", "$Width", "$Height") -NoNewWindow -PassThru -RedirectStandardOutput "$env:TEMP\fake_mw2_host.out"
Start-Sleep 2
$env:IW4L_GAMES = $Mw2
$env:IW4L_SKYCRAFT = "1"
$dir = Split-Path $Iw4l
$guest = Start-Process $Iw4l -ArgumentList @("map", "mp_rust", "--cmds", "`"$Cmds`"") -WorkingDirectory $dir -PassThru
$hostProc.WaitForExit()
if (-not $guest.HasExited) { Stop-Process -Id $guest.Id -Force }
Get-Content "$env:TEMP\fake_mw2_host.out"
$log = Get-ChildItem "$dir\iw4l-artifacts\logs" -Filter *.log | Sort-Object LastWriteTime -Descending | Select-Object -First 1
Select-String -Path $log.FullName -Pattern "SkyCraft|panic|Validation" | Select-Object -First 8 | ForEach-Object { $_.Line }
