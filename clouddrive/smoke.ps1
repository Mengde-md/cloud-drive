# 冒烟测试：对正在运行的服务真跑一遍核心业务链路。
#
# 前提：先用 run.ps1 把应用起起来，然后"另开一个 PowerShell 窗口"执行本脚本：
#   powershell -ExecutionPolicy Bypass -File .\smoke.ps1
#
# 结果同时打印到控制台并写入 smoke.log。

param(
    [string]$AliceEmail,
    [string]$BobEmail
)

$ErrorActionPreference = 'Continue'
$Base = 'http://localhost:8080'
$Log  = Join-Path $PSScriptRoot 'smoke.log'
$script:lines = @()
$script:pass = 0
$script:fail = 0

function Say($text, $color = 'Gray') {
    Write-Host $text -ForegroundColor $color
    $script:lines += $text
}

function Check($name, $ok, $detail) {
    if ($ok) {
        Say ("[PASS] " + $name) 'Green'
        $script:pass++
    }
    else {
        Say ("[FAIL] " + $name + "  -> " + $detail) 'Red'
        $script:fail++
    }
}

# JSON 请求：返回 @{ Status = <int>; Body = <object> }，4xx/5xx 不抛异常
function Api($method, $path, $body, $token) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    $params = @{
        Uri         = "$Base$path"
        Method      = $method
        Headers     = $headers
        ErrorAction = 'Stop'
    }
    if ($null -ne $body) {
        $params['Body'] = ($body | ConvertTo-Json -Compress)
        $params['ContentType'] = 'application/json; charset=utf-8'
    }
    try {
        $resp = Invoke-WebRequest @params -UseBasicParsing
        $parsed = $null

        # PowerShell 5.1 只对它认识的文本 Content-Type 把 Content 解码成字符串。
        # actuator 的 application/vnd.spring-boot.actuator.v3+json 不在此列，
        # 它返回的是 byte[]，直接 ConvertFrom-Json 会失败。这里统一先转字符串。
        $raw = $resp.Content
        if ($raw -is [byte[]]) { $raw = [Text.Encoding]::UTF8.GetString($raw) }
        if ($raw) { try { $parsed = $raw | ConvertFrom-Json } catch { } }

        return [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $parsed; Raw = $raw }
    }
    catch {
        $status = 0
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        return [pscustomobject]@{ Status = $status; Body = $null; Raw = '' }
    }
}

Say "===== CloudDrive smoke test =====" 'Cyan'
Say ("time: " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
Say ""

# ---------- 0. 服务在不在 ----------
$health = Api GET '/actuator/health' $null $null
Check "0. actuator/health 返回 UP" `
    ($health.Status -eq 200 -and ($health.Body.status -eq 'UP' -or $health.Raw -match 'UP')) `
    "status=$($health.Status) body=$($health.Raw)"
if ($health.Status -ne 200) {
    Say "服务没起来，先跑 run.ps1" 'Red'
    $script:lines | Out-File -FilePath $Log -Encoding utf8
    exit 1
}

$stamp = [DateTime]::Now.ToString('HHmmssfff')
$alice = "alice$stamp"
$bob   = "bob$stamp"
$pwd   = 'correct-password'

# 注册现在需要真实邮箱验证码，不再使用绕过邮箱验证的旧接口。
function Register-SmokeUser($username, $email) {
    $sent = Api POST '/api/auth/email-code' @{ email = $email; purpose = 'REGISTER' } $null
    if ($sent.Status -ne 200) {
        throw "邮箱验证码发送失败（HTTP $($sent.Status)），请检查 SMTP 配置和邮箱。"
    }
    $emailCode = Read-Host "请输入发送到 $email 的验证码"
    return Api POST '/api/auth/register' @{
        username = $username; password = $pwd; email = $email; code = $emailCode
    } $null
}

if (-not $AliceEmail) { $AliceEmail = Read-Host 'Alice 测试邮箱（未注册过）' }
if (-not $BobEmail) { $BobEmail = Read-Host 'Bob 测试邮箱（另一未注册邮箱）' }

# ---------- 1. 注册与登录 ----------
$r = Register-SmokeUser $alice $AliceEmail
Check "1. 注册返回 201" ($r.Status -eq 201) "status=$($r.Status)"
if ($r.Status -ne 201) { throw '注册失败，请使用尚未注册的真实邮箱重新运行。' }
$aliceId = $r.Body.id

$r = Api POST '/api/auth/login' @{ username = $alice; password = $pwd } $null
$aliceToken = $r.Body.accessToken
Check "2. 登录拿到 JWT" ($r.Status -eq 200 -and $aliceToken) "status=$($r.Status)"

$r = Api POST '/api/auth/login' @{ username = $alice; password = 'wrong-password' } $null
Check "3. 错误口令返回 401" ($r.Status -eq 401) "status=$($r.Status)"

$r = Api GET '/api/entries' $null $null
Check "4. 无令牌访问受保护接口返回 401" ($r.Status -eq 401) "status=$($r.Status)"

$r = Api GET '/api/auth/me' $null $aliceToken
Check "5. /me 返回配额信息" ($r.Status -eq 200 -and $r.Body.quotaBytes -gt 0) "quota=$($r.Body.quotaBytes)"

# ---------- 2. 目录与重名约束 ----------
$r = Api POST '/api/entries' @{ parentId = $null; displayName = 'docs'; kind = 'DIRECTORY'; byteSize = $null } $aliceToken
$dirId = $r.Body.id
Check "6. 创建目录返回 201" ($r.Status -eq 201 -and $dirId) "status=$($r.Status)"

$r = Api POST '/api/entries' @{ parentId = $null; displayName = 'docs'; kind = 'DIRECTORY'; byteSize = $null } $aliceToken
Check "7. 同级重名返回 409" ($r.Status -eq 409) "status=$($r.Status)"

$r = Api POST '/api/entries' @{ parentId = $null; displayName = '  '; kind = 'DIRECTORY'; byteSize = $null } $aliceToken
Check "8. 空白名参数校验返回 400" ($r.Status -eq 400) "status=$($r.Status)"

# ---------- 3. 上传：MD5 与秒传 ----------
$tmp = Join-Path $env:TEMP "clouddrive-smoke-$stamp.txt"
[IO.File]::WriteAllText($tmp, 'hello-day17', (New-Object Text.UTF8Encoding $false))
$expectedMd5 = 'c6973c299874592a250a2c5fc288f3b0'

$out = & curl.exe -s -w "`n%{http_code}" -X POST "$Base/api/files/upload" `
    -H "Authorization: Bearer $aliceToken" `
    -F "parentId=$dirId" -F "displayName=notes.txt" -F "file=@$tmp" 2>&1
$parts = ($out -join "`n") -split "`n"
$code = $parts[-1]
$upload = ($parts[0..($parts.Count - 2)] -join '') | ConvertFrom-Json
Check "9. multipart 上传返回 201" ($code -eq '201') "status=$code"
Check "10. 服务端算出的 MD5 正确" ($upload.md5 -eq $expectedMd5) "md5=$($upload.md5)"
Check "11. 服务端算出的字节数正确" ($upload.byteSize -eq 11) "size=$($upload.byteSize)"
$fileId = $upload.id

$r = Api POST '/api/files/instant' @{ parentId = $dirId; displayName = 'copy.txt'; md5 = $expectedMd5 } $aliceToken
Check "12. 秒传命中并标记 instant=true" ($r.Status -eq 201 -and $r.Body.instant -eq $true) "status=$($r.Status) instant=$($r.Body.instant)"

$r = Api POST '/api/files/instant' @{ parentId = $dirId; displayName = 'ghost.txt'; md5 = '0123456789abcdef0123456789abcdef' } $aliceToken
Check "13. 秒传未命中返回 404" ($r.Status -eq 404) "status=$($r.Status)"

# ---------- 4. 下载与 Range ----------
$body = & curl.exe -s "$Base/api/files/$fileId/download" -H "Authorization: Bearer $aliceToken"
Check "14. 完整下载内容一致" ($body -eq 'hello-day17') "body=$body"

$hdrFile = Join-Path $env:TEMP "clouddrive-smoke-hdr-$stamp.txt"
$part = & curl.exe -s -D $hdrFile "$Base/api/files/$fileId/download" `
    -H "Authorization: Bearer $aliceToken" -H "Range: bytes=0-4"
$hdr = Get-Content $hdrFile -Raw
Check "15. Range 请求返回 206" ($hdr -match '206') "first line: $(($hdr -split "`n")[0].Trim())"
Check "16. Content-Range 头正确" ($hdr -match 'bytes 0-4/11') "header missing"
Check "17. Range 内容只有请求的那几个字节" ($part -eq 'hello') "body=$part"

# ---------- 5. 分享 ----------
$r = Api POST '/api/shares' @{ documentId = $fileId; passcode = '2468'; expiresAt = $null } $aliceToken
$shareToken = $r.Body.token
Check "18. 创建分享返回 201" ($r.Status -eq 201 -and $shareToken) "status=$($r.Status)"

$out = & curl.exe -s -o NUL -w "%{http_code}" "$Base/api/shares/$shareToken/download?passcode=wrong"
Check "19. 提取码错误返回 404" ($out -eq '404') "status=$out"

$shared = & curl.exe -s "$Base/api/shares/$shareToken/download?passcode=2468"
Check "20. 匿名凭正确提取码可下载" ($shared -eq 'hello-day17') "body=$shared"

# ---------- 6. 用户隔离 ----------
$bobRegistered = Register-SmokeUser $bob $BobEmail
if ($bobRegistered.Status -ne 201) { throw 'Bob 注册失败，请使用另一未注册的真实邮箱。' }
$bobToken = (Api POST '/api/auth/login' @{ username = $bob; password = $pwd } $null).Body.accessToken

$r = Api GET "/api/entries/$fileId" $null $bobToken
Check "21. 他人访问别人的条目返回 404（不是 403）" ($r.Status -eq 404) "status=$($r.Status)"

$r = Api GET '/api/entries' $null $bobToken
Check "22. 他人列表为空" ($r.Status -eq 200 -and (@($r.Body).Count -eq 0)) "count=$(@($r.Body).Count)"

# ---------- 7. 回收站 ----------
$r = Api DELETE "/api/entries/$fileId" $null $aliceToken
Check "23. 删除返回 200 并报告受影响条目数" ($r.Status -eq 200 -and $r.Body.affectedCount -ge 1) "status=$($r.Status) affected=$($r.Body.affectedCount)"

$r = Api GET "/api/entries/$fileId" $null $aliceToken
Check "24. 删除后正常路径查不到（404）" ($r.Status -eq 404) "status=$($r.Status)"

$r = Api GET '/api/entries/trash' $null $aliceToken
Check "25. 回收站里能看到它" ($r.Status -eq 200 -and $r.Body.total -ge 1) "total=$($r.Body.total)"

$r = Api GET '/api/entries/trash' $null $bobToken
Check "26. 回收站同样受用户隔离" ($r.Status -eq 200 -and $r.Body.total -eq 0) "total=$($r.Body.total)"

$r = Api POST "/api/entries/trash/$fileId/restore" $null $aliceToken
Check "27. 恢复返回 200 并报告受影响条目数" ($r.Status -eq 200 -and $r.Body.affectedCount -ge 1) "status=$($r.Status) affected=$($r.Body.affectedCount)"

$r = Api GET "/api/entries/$fileId" $null $aliceToken
Check "28. 恢复后又能查到" ($r.Status -eq 200 -and $r.Body.displayName -eq 'notes.txt') "name=$($r.Body.displayName)"

# ---------- 8. 分页与快照 ----------
$r = Api GET '/api/entries/page?page=0&size=2' $null $aliceToken
Check "29. 分页返回 page/size/total" ($r.Status -eq 200 -and $r.Body.size -eq 2 -and $r.Body.total -ge 1) "total=$($r.Body.total)"

$r = Api GET '/api/entries/page?page=0&size=999' $null $aliceToken
Check "30. 非法分页参数返回 400" ($r.Status -eq 400) "status=$($r.Status)"

$r = Api POST '/api/snapshots' $null $aliceToken
Check "31. 快照任务返回条目数与耗时" ($r.Status -eq 200 -and $r.Body.entryCount -ge 1) "count=$($r.Body.entryCount)"

# ---------- 9. 级联删除（V8 新增） ----------
$r = Api POST '/api/entries' @{ parentId = $null; displayName = "cascade$stamp"; kind = 'DIRECTORY'; byteSize = $null } $aliceToken
$cascadeDir = $r.Body.id
$r = Api POST '/api/entries' @{ parentId = $cascadeDir; displayName = 'inner.txt'; kind = 'DOCUMENT'; byteSize = 5 } $aliceToken
$innerId = $r.Body.id
Check "32. 在目录内创建子文件" ($r.Status -eq 201 -and $innerId) "status=$($r.Status)"

$r = Api DELETE "/api/entries/$cascadeDir" $null $aliceToken
Check "33. 删除目录级联影响 2 项（目录+子文件）" ($r.Status -eq 200 -and $r.Body.affectedCount -eq 2) "affected=$($r.Body.affectedCount)"

$r = Api GET "/api/entries/$innerId" $null $aliceToken
Check "34. 子文件也进了回收站，不再是幽灵条目" ($r.Status -eq 404) "status=$($r.Status)"

$r = Api GET '/api/entries/trash?page=0&size=50' $null $aliceToken
$rootRows  = @($r.Body.items | Where-Object { $_.id -eq $cascadeDir })
$innerRows = @($r.Body.items | Where-Object { $_.id -eq $innerId })
Check "35. 回收站只列出被删的目录，不铺开子条目" (($rootRows.Count -eq 1) -and ($innerRows.Count -eq 0)) "root=$($rootRows.Count) inner=$($innerRows.Count)"

$r = Api POST "/api/entries/trash/$cascadeDir/restore" $null $aliceToken
Check "36. 恢复目录时整棵子树一起回来" ($r.Status -eq 200 -and $r.Body.affectedCount -eq 2) "affected=$($r.Body.affectedCount)"

$r = Api GET "/api/entries/$innerId" $null $aliceToken
Check "37. 子文件随目录恢复后可再次访问" ($r.Status -eq 200) "status=$($r.Status)"

# ---------- 10. 分享详情 / 转存 / 重置提取码 ----------
$r = Api GET "/api/shares/$shareToken/info?passcode=2468" $null $null
Check "38. 匿名可查分享文件信息（含文件名与大小）" `
    ($r.Status -eq 200 -and $r.Body.displayName -eq 'notes.txt' -and $r.Body.byteSize -eq 11) `
    "status=$($r.Status) name=$($r.Body.displayName)"

$r = Api GET "/api/shares/$shareToken/info?passcode=wrong" $null $null
Check "39. 提取码错误查详情同样返回 404" ($r.Status -eq 404) "status=$($r.Status)"

# bob 把 alice 分享的文件转存到自己网盘
$r = Api POST "/api/shares/$shareToken/save" @{ passcode = '2468'; parentId = $null; displayName = $null } $bobToken
Check "40. 转存返回 201 且落在转存者名下" ($r.Status -eq 201 -and $r.Body.displayName -eq 'notes.txt') "status=$($r.Status)"

$r = Api GET '/api/entries' $null $bobToken
Check "41. 转存后他的网盘里出现了这个文件" ($r.Status -eq 200 -and (@($r.Body).Count -ge 1)) "count=$(@($r.Body).Count)"

$r = Api POST "/api/shares/$shareToken/save" @{ passcode = 'wrong'; parentId = $null; displayName = $null } $bobToken
Check "42. 提取码错误不能转存" ($r.Status -eq 404) "status=$($r.Status)"

$r = Api POST "/api/shares/$shareToken/save" @{ passcode = '2468'; parentId = $null; displayName = $null } $null
Check "43. 未登录不能转存（转存需身份）" ($r.Status -eq 401) "status=$($r.Status)"

# 重置提取码：链接不变、旧码失效、新码可用
$r = Api PUT "/api/shares/$shareToken/passcode" @{ passcode = '13579' } $aliceToken
Check "44. 所有者可重置提取码" ($r.Status -eq 204) "status=$($r.Status)"

$r = Api GET "/api/shares/$shareToken/info?passcode=2468" $null $null
Check "45. 重置后旧提取码立即失效" ($r.Status -eq 404) "status=$($r.Status)"

$r = Api GET "/api/shares/$shareToken/info?passcode=13579" $null $null
Check "46. 新提取码可用且链接未变" ($r.Status -eq 200) "status=$($r.Status)"

$r = Api PUT "/api/shares/$shareToken/passcode" @{ passcode = '99999' } $bobToken
Check "47. 他人不能重置别人分享的提取码" ($r.Status -eq 404) "status=$($r.Status)"

$r = Api GET '/api/shares' $null $aliceToken
$mine = @($r.Body | Where-Object { $_.token -eq $shareToken })
Check "48. 分享列表带出文件名，不再只有 ID" `
    ($r.Status -eq 200 -and $mine.Count -eq 1 -and $mine[0].displayName -eq 'notes.txt') `
    "name=$($mine[0].displayName)"

# ---------- 结果 ----------
Remove-Item $tmp, $hdrFile -ErrorAction SilentlyContinue
Say ""
Say "===== result =====" 'Cyan'
Say ("PASS: $script:pass   FAIL: $script:fail")
$script:lines | Out-File -FilePath $Log -Encoding utf8
Write-Host ""
Write-Host "full log: $Log"
