param([int]$Port=18185)
$ErrorActionPreference='Stop'
$listener=[Net.HttpListener]::new()
$listener.Prefixes.Add("http://127.0.0.1:$Port/")
$listener.Start()
$mode='valid';$calls=0
try {
    while($listener.IsListening) {
        $context=$listener.GetContext()
        try {
            $path=$context.Request.Url.AbsolutePath
            $status=200
            if($path -eq '/health'){$body=@{status='UP';protocolFixture=$true}}
            elseif($path -eq '/stats'){$body=@{calls=$calls;protocolFixture=$true}}
            elseif($path -eq '/mode/forged'){$mode='forged';$body=@{mode=$mode}}
            elseif($path -eq '/mode/valid'){$mode='valid';$body=@{mode=$mode}}
            elseif($path -eq '/v1/chat/completions' -and $context.Request.HttpMethod -eq 'POST') {
                $calls++
                $reader=[IO.StreamReader]::new($context.Request.InputStream,[Text.Encoding]::UTF8)
                try{$request=$reader.ReadToEnd()|ConvertFrom-Json}finally{$reader.Dispose()}
                $prompt=$request.messages[1].content|ConvertFrom-Json
                $hit=$prompt.evidence[0]
                $id=if($mode -eq 'forged'){'unknown-owner:0'}else{$hit.id}
                $content=@{status='ANSWERED';answer='Deleted files are retained for thirty days.'
                    citations=@(@{id=$id;quote=$hit.text})}|ConvertTo-Json -Depth 8 -Compress
                $body=@{id='day31-protocol-fixture';model='deepseek-flash'
                    choices=@(@{index=0;message=@{role='assistant';content=$content};finish_reason='stop'})
                    usage=@{prompt_tokens=100;completion_tokens=50;total_tokens=150}}
            } else {$status=404;$body=@{error='fixture_route_not_found'}}
            $bytes=[Text.Encoding]::UTF8.GetBytes(($body|ConvertTo-Json -Depth 12 -Compress))
            $context.Response.StatusCode=$status;$context.Response.ContentType='application/json'
            $context.Response.ContentLength64=$bytes.Length
            $context.Response.OutputStream.Write($bytes,0,$bytes.Length)
        } finally {$context.Response.Close()}
    }
} finally {$listener.Stop();$listener.Close()}
