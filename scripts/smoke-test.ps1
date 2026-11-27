param(
    [string]$BaseUrl = 'http://127.0.0.1:80'
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$unique = "$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())-$([guid]::NewGuid().ToString('N').Substring(0,6))"
$email = "smoke-$unique@livevote.local"
$password = "SmokePass_${unique}!"
$device1 = "smoke-dev-1-$unique"
$device2 = "smoke-dev-2-$unique"

function Invoke-Api([string]$Method, [string]$Path, [hashtable]$Headers = @{}, [string]$Body = '') {
    try {
        $params = @{
            UseBasicParsing = $true
            Method          = $Method
            Uri             = "$BaseUrl$Path"
            Headers         = $Headers
            TimeoutSec      = 10
        }
        if (-not [string]::IsNullOrEmpty($Body)) {
            $params['ContentType'] = 'application/json'
            $params['Body'] = $Body
        }
        $resp = Invoke-WebRequest @params
        return [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $resp.Content }
    } catch {
        $exResp = $_.Exception.Response
        if ($null -eq $exResp) {
            return [pscustomobject]@{ Status = 0; Body = $_.Exception.Message }
        }
        $sr = New-Object System.IO.StreamReader($exResp.GetResponseStream())
        return [pscustomobject]@{ Status = [int]$exResp.StatusCode; Body = $sr.ReadToEnd() }
    }
}

function Assert-Code([string]$Label, $Resp, [int]$Expected) {
    if ($Resp.Status -ne $Expected) {
        throw "FAIL $Label : expected HTTP $Expected, got $($Resp.Status). Body: $($Resp.Body)"
    }
    Write-Output "PASS $Label (HTTP $($Resp.Status))"
}

# 1. Healthz
$r1 = Invoke-Api 'GET' '/healthz'
Assert-Code '[1/9] GET /healthz' $r1 200

# 2. Spoofed X-User-Id rejected with 401
$r2 = Invoke-Api 'GET' '/api/polls/mine' @{ 'X-User-Id' = '00000000-0000-0000-0000-000000000001' }
Assert-Code '[2/9] Spoofed X-User-Id rejected' $r2 401

# 3. Register
$r3 = Invoke-Api 'POST' '/api/auth/register' @{} (@{ email = $email; password = $password } | ConvertTo-Json)
Assert-Code '[3/9] POST /api/auth/register' $r3 201

# 4. Login
$r4 = Invoke-Api 'POST' '/api/auth/login' @{} (@{ email = $email; password = $password } | ConvertTo-Json)
Assert-Code '[4/9] POST /api/auth/login' $r4 200
$token = ($r4.Body | ConvertFrom-Json).token
if ([string]::IsNullOrWhiteSpace($token)) { throw 'Missing JWT token in login response' }

# 5. Create poll
$r5 = Invoke-Api 'POST' '/api/polls' @{ Authorization = "Bearer $token" } (@{ title = 'Smoke Prod Poll'; options = @('Alpha', 'Beta') } | ConvertTo-Json)
Assert-Code '[5/9] POST /api/polls' $r5 201
$poll = $r5.Body | ConvertFrom-Json
$pollId = $poll.id
$optionA = $poll.options[0].id

# 6. Wait for initial projection
$projReady = $false
$deadline = (Get-Date).AddSeconds(15)
while ((Get-Date) -lt $deadline) {
    $r6 = Invoke-Api 'GET' "/api/results/$pollId"
    if ($r6.Status -eq 200) { $projReady = $true; break }
    Start-Sleep -Milliseconds 300
}
if (-not $projReady) { throw 'FAIL [6/9] Projection not ready in time' }
Write-Output 'PASS [6/9] Initial results projection ready (HTTP 200)'

# 7. Vote + results totalVotes=1 + SSE stream snapshot
$r7 = Invoke-Api 'POST' "/api/polls/$pollId/votes" @{} (@{ optionId = $optionA; deviceId = $device1 } | ConvertTo-Json)
Assert-Code '[7a/9] POST vote' $r7 201

$voteProjected = $false
$vDeadline = (Get-Date).AddSeconds(15)
while ((Get-Date) -lt $vDeadline) {
    $res = Invoke-Api 'GET' "/api/results/$pollId"
    if ($res.Status -eq 200 -and ($res.Body | ConvertFrom-Json).totalVotes -eq 1) {
        $voteProjected = $true
        break
    }
    Start-Sleep -Milliseconds 300
}
if (-not $voteProjected) { throw 'FAIL [7b/9] totalVotes did not reach 1' }

$tmpSse = [System.IO.Path]::GetTempFileName()
$tmpErr = [System.IO.Path]::GetTempFileName()
$proc = Start-Process -FilePath 'curl.exe' -ArgumentList @('-N', '-sS', "$BaseUrl/api/results/$pollId/stream") -RedirectStandardOutput $tmpSse -RedirectStandardError $tmpErr -NoNewWindow -PassThru
try {
    $sseOk = $false
    $sDeadline = (Get-Date).AddSeconds(6)
    while ((Get-Date) -lt $sDeadline) {
        $fs = [System.IO.File]::Open($tmpSse, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        try {
            $txt = (New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)).ReadToEnd()
        } finally {
            $fs.Dispose()
        }
        if ($txt -match 'event:results' -and $txt -match '"totalVotes":1') { $sseOk = $true; break }
        Start-Sleep -Milliseconds 200
    }
    if (-not $sseOk) { throw 'FAIL [7c/9] SSE snapshot did not emit totalVotes:1' }
    Write-Output 'PASS [7/9] Vote projected (totalVotes=1) and live SSE stream verified'
} finally {
    if ($null -ne $proc -and -not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -Force -ErrorAction SilentlyContinue $tmpSse, $tmpErr
}

# 8. Duplicate vote 409
$r8 = Invoke-Api 'POST' "/api/polls/$pollId/votes" @{} (@{ optionId = $optionA; deviceId = $device1 } | ConvertTo-Json)
Assert-Code '[8/9] Duplicate vote rejected' $r8 409

# 9. Close poll -> new vote 422
$r9a = Invoke-Api 'POST' "/api/polls/$pollId/close" @{ Authorization = "Bearer $token" }
Assert-Code '[9a/9] Close poll' $r9a 200
$r9b = Invoke-Api 'POST' "/api/polls/$pollId/votes" @{} (@{ optionId = $optionA; deviceId = $device2 } | ConvertTo-Json)
Assert-Code '[9b/9] Vote after close rejected' $r9b 422

Write-Output "ALL 9 SMOKE CHECKS PASSED against $BaseUrl"
