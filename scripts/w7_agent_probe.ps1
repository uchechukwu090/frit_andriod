# Week 7 - live design-agent probe (PowerShell equivalent of
# scripts/w6_design_agent_probe.sh; WSL bash is not installed on this host).
#
# Boots the server on a throwaway DB, grants the test install an owner tier so
# the paywall does not mask the pipeline, then runs the agent TWICE with the
# SAME goal and prints both trails. The second run is the point of the probe:
# the same request must NOT come back with the same references, the same
# brief or the same prompt - that was the "the AI is not creative" complaint.
#
# Costs two image generations.
#
#   powershell -ExecutionPolicy Bypass -File scripts/w7_agent_probe.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/w7_agent_probe.ps1 -Goal "..." -NoRender
param(
  [string]$Goal = "Instagram flyer for a Lagos birthday party, warm and celebratory",
  [string]$Headline = "HAPPY BIRTHDAY",
  [string]$Cta = "Join the party",
  [int]$MaxRefs = 3,
  [int]$MaxAttempts = 2,
  [switch]$NoRender,
  [int]$Port = 18844
)

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$server = Join-Path $root "server"
if (-not (Test-Path $server)) { Write-Error "server/ not found"; exit 2 }

$db = Join-Path $env:TEMP ("frit_w7_probe_{0}.db" -f $PID)
$log = Join-Path $env:TEMP ("frit_w7_probe_{0}.log" -f $PID)
$base = "http://127.0.0.1:$Port"
$env:PORT = "$Port"
$env:DB_PATH = $db
Remove-Item $db, "$db-wal", "$db-shm" -ErrorAction SilentlyContinue

$proc = Start-Process -FilePath "node" -ArgumentList "src/index.js" -WorkingDirectory $server `
  -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -NoNewWindow

function Cleanup {
  if ($proc -and -not $proc.HasExited) {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 500
    if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
  }
  Remove-Item $db, "$db-wal", "$db-shm" -ErrorAction SilentlyContinue
}
trap { Cleanup; break }

try {
  $up = $false
  for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 1
    if ($proc.HasExited) { Write-Host "server died:"; Get-Content $log -Tail 30; exit 1 }
    try { Invoke-RestMethod "$base/health" -TimeoutSec 2 | Out-Null; $up = $true; break } catch { }
  }
  if (-not $up) { Write-Host "server did not come up"; Get-Content $log -Tail 30; exit 1 }

  $uid = "app_a1b2c3d4e5f6"
  $key = (Invoke-RestMethod -Method Post -Uri "$base/auth/install" -ContentType "application/json" `
    -Body (@{ install_id = $uid } | ConvertTo-Json)).api_key
  if (-not $key) { Write-Host "no api key"; exit 1 }

  # Owner tier so the paywall does not mask the pipeline. Redeeming the
  # MASTER_CODE is the server's own path (POST /billing/redeem) - it needs no
  # direct DB write, so it cannot race the server's own schema/backup logic.
  $master = ""
  $envFile = Join-Path $server ".env"
  if (Test-Path $envFile) {
    $line = Get-Content $envFile | Where-Object { $_ -match "^\s*MASTER_CODE\s*=" } | Select-Object -First 1
    if ($line) { $master = ($line -split "=", 2)[1].Trim().Trim('"').Trim("'") }
  }
  if (-not $master) { Write-Host "MASTER_CODE not found in server/.env - cannot grant the tier"; exit 1 }
  $redeem = Invoke-RestMethod -Method Post -Uri "$base/billing/redeem" -Headers @{ Authorization = "Bearer $key" } `
    -ContentType "application/json" -Body (@{ code = $master } | ConvertTo-Json)
  Write-Host ("  tier: {0}" -f $redeem.tier)

  $results = @()
  foreach ($run in 1..2) {
    Write-Host "== run $run =="
    $body = @{
      goal = $Goal; headline = $Headline; cta = $Cta
      aspect_ratio = "4:5"; max_refs = $MaxRefs; maxAttempts = $MaxAttempts
    }
    if ($NoRender) { $body.verify = $false }
    $json = $body | ConvertTo-Json -Compress
    $sw = [Diagnostics.Stopwatch]::StartNew()
    # curl.exe, not Invoke-RestMethod: a 90s+ request that returns a
    # multi-megabyte payload loses its keep-alive socket in .NET's HTTP
    # stack on this host (the server completes it with a 200 every time).
    # The payload goes through a FILE: passing JSON inline to curl.exe from
    # PowerShell strips the quotes and the server sees broken JSON.
    $bodyFile = Join-Path $env:TEMP ("w7_body_{0}.json" -f $PID)
    $postFile = Join-Path $env:TEMP ("w7_post_{0}.json" -f $PID)
    Set-Content -Path $postFile -Value $json -Encoding ASCII
    $code = & curl.exe -sS --max-time 300 -o $bodyFile -w "%{http_code}" -X POST "$base/creative-suite/design/agent" `
      -H "Authorization: Bearer $key" -H "Content-Type: application/json" --data-binary "@$postFile"
    $sw.Stop()
    $raw = Get-Content $bodyFile -Raw -ErrorAction SilentlyContinue
    Remove-Item $bodyFile, $postFile -ErrorAction SilentlyContinue
    if ($code -ne "200") { Write-Host "  HTTP $code : $($raw.Substring(0, [Math]::Min(300, $raw.Length)))"; exit 1 }
    $r = $raw | ConvertFrom-Json

    $refs = @($r.inspiration.refs)
    $studied = @($r.inspiration.studied)
    Write-Host ("  ok={0}  {1}ms  seed={2}  style='{3}'" -f $r.ok, $sw.ElapsedMilliseconds, $r.seed, $r.style_token)
    Write-Host ("  queries : {0}" -f ($r.inspiration.queries -join " | "))
    Write-Host ("  refs    : {0} found, {1} read, {2} studied" -f $refs.Count, $r.inspiration.read, $studied.Count)
    foreach ($s in $studied) { Write-Host ("     - {0}  [{1}]" -f $s.ref, ($s.structure.grid + "/" + $s.structure.alignment + "/" + $s.structure.palette_temp)) }
    Write-Host ("  layout  : {0} / {1} / focal {2}" -f $r.brief.layout.grid, $r.brief.layout.alignment, $r.brief.layout.focal)
    Write-Host ("  palette : {0}" -f (($r.brief.palette | ForEach-Object { "$($_.hex) $($_.share_pct)%" }) -join ", "))
    Write-Host ("  type    : {0}" -f (($r.brief.typography | ForEach-Object { "$($_.role) $($_.font) $($_.size_px)px" }) -join ", "))
    Write-Host ("  art dir : {0}" -f $r.art_direction)
    Write-Host ("  diverge : {0}" -f (($r.divergence.changed) -join ", "))
    Write-Host ("  render  : model={0} url={1} b64={2} type_layer={3}" -f $r.render.model_used, [bool]$r.render.image_url, [bool]$r.render.image_b64, $r.render.type_layer)
    Write-Host ("  verify  : {0}" -f ($r.verification | ConvertTo-Json -Compress -Depth 4))
    if ($r.warnings) { foreach ($w in $r.warnings) { Write-Host ("  warn    : {0}" -f $w) } }
    $results += $r
  }

  Write-Host ""
  Write-Host "== repeat-request check (the 'is it creative?' test) =="
  $a = $results[0]; $b = $results[1]
  $refA = @($a.inspiration.studied | ForEach-Object { $_.ref })
  $refB = @($b.inspiration.studied | ForEach-Object { $_.ref })
  $sameRefs = ((Compare-Object $refA $refB -SyncWindow 0).Count -eq 0)
  Write-Host ("  seeds        : {0} vs {1}  -> {2}" -f $a.seed, $b.seed, $(if ($a.seed -ne $b.seed) { "DIFFERENT" } else { "SAME (bad)" }))
  Write-Host ("  references   : {0}" -f $(if (-not $sameRefs) { "DIFFERENT" } else { "SAME (bad)" }))
  Write-Host ("  art direction: {0}" -f $(if ($a.art_direction -ne $b.art_direction) { "DIFFERENT" } else { "SAME (bad)" }))
  Write-Host ("  prompt       : {0}" -f $(if ($a.prompt -ne $b.prompt) { "DIFFERENT" } else { "SAME (bad)" }))
  Write-Host ("  brief layout : {0}" -f $(if (($a.brief.layout | ConvertTo-Json -Compress) -ne ($b.brief.layout | ConvertTo-Json -Compress)) { "DIFFERENT" } else { "SAME (bad)" }))
  $ok = ($a.seed -ne $b.seed) -and (-not $sameRefs) -and ($a.prompt -ne $b.prompt)
  Write-Host ""
  Write-Host $(if ($ok) { "PROBE PASS - the same request browsed and drew something different." } else { "PROBE FAIL - the repeat request repeated itself." })
  if (-not $ok) { exit 1 }
}
finally {
  Write-Host ""
  Write-Host "---- server log (agent lines) ----"
  if (Test-Path $log) { Get-Content $log | Select-String -Pattern "design|agent|Error|error|warn" | Select-Object -Last 25 }
  Cleanup
}




