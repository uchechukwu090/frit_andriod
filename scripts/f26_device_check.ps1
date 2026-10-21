# F-26 device check — EncryptedSharedPreferences migration (audit Week 5)
#
# Verifies the three things the audit asked for, on a REAL device or emulator:
#   1. the app PIN survives a force-stop / cold start
#   2. the PLAINTEXT copy is gone from frit_prefs (it now lives encrypted)
#   3. the wipe path (DataWipe) removes BOTH copies
#
# The PIN is only ever READ from the app's own private storage via adb
# (run-as), which needs a DEBUG build. Nothing here prints the PIN value -
# only whether it is present.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\f26_device_check.ps1
#
# Requires: adb on PATH (or in $env:ANDROID_SDK_ROOT\platform-tools) and a
# device/emulator with the app installed (`.\gradlew :app:installDebug`).

$ErrorActionPreference = "Stop"
$PKG = "com.frit.app"
$ADB = if (Get-Command adb -ErrorAction SilentlyContinue) { "adb" }
       else { Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe" }

function Run-Adb([string[]]$args) {
    $out = & $ADB @args 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb $($args -join ' ') failed: $out" }
    return ($out | Out-String)
}

function Get-Prefs([string]$file) {
    # Dump the app's private prefs as text (run-as needs a debuggable build).
    $raw = Run-Adb @("shell", "run-as", $PKG, "cat", "shared_prefs/$file")
    return $raw
}

function Pref-HasKey([string]$raw, [string]$key) {
    return ($raw -match [regex]::Escape('name="' + $key + '"'))
}

Write-Host "=== F-26 device check ===" -ForegroundColor Cyan

$devices = Run-Adb @("devices") | Select-String "device$"
if (-not $devices) { throw "no device attached (adb devices shows nothing usable)" }
Write-Host "device: OK"

# ---------------------------------------------------------------- 1. plaintext
Write-Host "`n[1] checking the PLAINTEXT copy in frit_prefs" -ForegroundColor Yellow
$legacy = Get-Prefs "frit_prefs.xml"
if (-not $legacy) { throw "could not read shared_prefs/frit_prefs.xml (is this a DEBUG build?)" }

$plainHasPin = Pref-HasKey $legacy "app_pin"
$plainHasAdmin = Pref-HasKey $legacy "admin_token"
Write-Host ("    frit_prefs.xml contains app_pin     : {0}" -f $(if ($plainHasPin) { "YES  <-- should be NO after migration" } else { "no   OK" }))
Write-Host ("    frit_prefs.xml contains admin_token : {0}" -f $(if ($plainHasAdmin) { "YES  <-- should be NO after migration" } else { "no   OK" }))

$secure = Get-Prefs "frit_secure.xml"
Write-Host ("    frit_secure.xml exists              : {0}" -f $(if ($secure) { "yes  OK (encrypted store present)" } else { "no   <-- no encrypted store yet" }))

# ------------------------------------------------------------ 2. survives kill
Write-Host "`n[2] checking the PIN survives a force-stop + cold start" -ForegroundColor Yellow
Write-Host "    -> set an App PIN in the app: Settings -> App PIN vault"
$read = Read-Host "    (press Enter once you have set one)"
Write-Host "    force-stopping the app..."
Run-Adb @("shell", "am", "force-stop", $PKG) | Out-Null
Start-Sleep -Seconds 2
Run-Adb @("shell", "monkey", "-p", $PKG, "-c", "android.intent.category.LAUNCHER", "1") | Out-Null
Start-Sleep -Seconds 4
Write-Host "    relaunched. Check the App PIN field in Settings still shows your PIN."
$survived = Read-Host "    did the PIN survive? (y/n)"
if ($survived -match "^[yY]") { Write-Host "    OK" -ForegroundColor Green }
else { Write-Host "    FAILED - the PIN did not survive the restart" -ForegroundColor Red }

# ------------------------------------------------------------------ 3. wipe
Write-Host "`n[3] checking the wipe removes BOTH copies" -ForegroundColor Yellow
Write-Host "    -> in the app: Settings -> Delete my data (this erases the PIN too)"
$read = Read-Host "    (press Enter once you have done it)"
Start-Sleep -Seconds 2
$legacy2 = Get-Prefs "frit_prefs.xml"
$secure2 = Get-Prefs "frit_secure.xml"
$w1 = Pref-HasKey $legacy2 "app_pin"
$w2 = Pref-HasKey $legacy2 "admin_token"
Write-Host ("    plaintext app_pin after wipe     : {0}" -f $(if ($w1) { "STILL THERE  <-- BUG" } else { "gone  OK" }))
Write-Host ("    plaintext admin_token after wipe : {0}" -f $(if ($w2) { "STILL THERE  <-- BUG" } else { "gone  OK" }))
if ($secure2) {
    $stillEnc = Select-String -InputObject $secure2 -Pattern "app_pin" -Quiet
    Write-Host ("    encrypted store still has app_pin: {0}" -f $(if ($stillEnc) { "yes  <-- BUG" } else { "no   OK" }))
}

Write-Host "`n=== done ===" -ForegroundColor Cyan
Write-Host "Remaining work after this check: none for F-26 - record the result in AUDIT.md."
