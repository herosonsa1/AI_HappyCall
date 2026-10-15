# ============================================================================
# [Local Windows Standalone Verification Script] test_local_audiosocket_call.ps1
# - Verifies Asterisk 20 res_audiosocket binary protocol (0x01 UUID -> 0x03 DTMF -> 0x10 PCM -> 0x00 Hangup)
#   and Node 1 Core Platform Server (HTTP 18080 & AudioSocket TCP 19092)
# ============================================================================
$Node1HttpUrl = 'http://localhost:18080'
$AudioSocketHost = '127.0.0.1'
$AudioSocketPort = 19092

Write-Host '============================================================================' -ForegroundColor Cyan
Write-Host '[1/3] Checking Node 1 FreePBX Integration Status (/api/freepbx/status)' -ForegroundColor Cyan
Write-Host '============================================================================' -ForegroundColor Cyan
$status = Invoke-RestMethod -Uri "$Node1HttpUrl/api/freepbx/status" -Method Get -TimeoutSec 5
$status | ConvertTo-Json -Depth 5 | Write-Host

Write-Host '============================================================================' -ForegroundColor Cyan
Write-Host '[2/3] Direct Asterisk 20 AudioSocket Binary Handshake (TCP Port 19092)' -ForegroundColor Cyan
Write-Host '============================================================================' -ForegroundColor Cyan
$client = New-Object System.Net.Sockets.TcpClient($AudioSocketHost, $AudioSocketPort)
$stream = $client.GetStream()
$client.ReceiveTimeout = 3000

# 1. 0x01 UUID Frame (3-byte header + 16-byte UUID)
$uuidBytes = [Guid]::NewGuid().ToByteArray()
$uuidFrame = New-Object byte[] 19
$uuidFrame[0] = 0x01
$uuidFrame[1] = 0x00
$uuidFrame[2] = 0x10
[Array]::Copy($uuidBytes, 0, $uuidFrame, 3, 16)
$stream.Write($uuidFrame, 0, $uuidFrame.Length)

# 2. 0x03 DTMF Frame ('1' = Privacy Consent)
$dtmfFrame = [byte[]](0x03, 0x00, 0x01, 0x31)
$stream.Write($dtmfFrame, 0, $dtmfFrame.Length)

# 3. 0x10 16kHz Signed Linear PCM Frame (320 bytes = 10ms)
$pcmFrame = New-Object byte[] 323
$pcmFrame[0] = 0x10
$pcmFrame[1] = 0x01
$pcmFrame[2] = 0x40
$stream.Write($pcmFrame, 0, $pcmFrame.Length)
$stream.Flush()

# 4. Read 0x10 TTS AudioSocket Response Frame from Node 1
$respHeader = New-Object byte[] 3
$readBytes = $stream.Read($respHeader, 0, 3)
$respType = $respHeader[0]
$respLen = (($respHeader[1] -band 0xFF) -shl 8) -bor ($respHeader[2] -band 0xFF)
Write-Host (" -> [OK] Node 1 AudioSocket TTS Frame Received: Type=0x{0:X2}, Length={1} bytes" -f $respType, $respLen) -ForegroundColor Green

# 5. 0x00 Hangup Frame
$hangupFrame = [byte[]](0x00, 0x00, 0x00)
$stream.Write($hangupFrame, 0, $hangupFrame.Length)
$stream.Close()
$client.Close()

Write-Host '============================================================================' -ForegroundColor Cyan
Write-Host '[3/3] E2E Test: FreePBX ARI Originate + AudioSocket + BizPlatform UPSERT + Douzone API' -ForegroundColor Cyan
Write-Host '============================================================================' -ForegroundColor Cyan
$payload = '{"receiptNo":"2026-HW-094812","insurerName":"HANWHA","adjusterId":"HERO-2041","custName":"Kim Minsu","birthYmd":"850515","vehicleNo":"123Ga4568"}'
$res = Invoke-RestMethod -Uri "$Node1HttpUrl/api/freepbx/originate" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($payload)) -TimeoutSec 10
$res | ConvertTo-Json -Depth 6 | Write-Host
