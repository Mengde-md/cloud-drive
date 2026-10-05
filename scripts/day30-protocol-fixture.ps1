# Explicit local protocol simulator, never a model or a production backend.
[CmdletBinding()]
param([int]$Port=18085)
$ErrorActionPreference='Stop'
$listener=[Net.HttpListener]::new()
$listener.Prefixes.Add("http://127.0.0.1:$Port/")
$listener.Start()
try{
    while($listener.IsListening){
        $context=$listener.GetContext()
        $response=$context.Response
        try{
            if($context.Request.Url.AbsolutePath -eq '/health'){
                $body=[Text.Encoding]::UTF8.GetBytes('{"status":"UP","protocolFixture":true}')
                $response.ContentType='application/json';$response.OutputStream.Write($body,0,$body.Length)
                continue
            }
            if($context.Request.Url.AbsolutePath -ne '/v1/chat/completions'){$response.StatusCode=404;continue}
            $reader=[IO.StreamReader]::new($context.Request.InputStream)
            try{$request=$reader.ReadToEnd()|ConvertFrom-Json}finally{$reader.Dispose()}
            $text=$request.messages[1].content
            $content=if($text -eq 'invalid'){'{"action":"delete"}'}else{'{"summary":"Packaged protocol summary","keywords":["CloudDrive"]}'}
            $usage=@{prompt_tokens=100;completion_tokens=50;total_tokens=150}
            if($request.stream){
                $response.ContentType='text/event-stream';$response.SendChunked=$true
                foreach($piece in @($content.Substring(0,10),$content.Substring(10))){
                    $chunk=@{id='packaged-fixture';model='deepseek-flash';choices=@(@{index=0;delta=@{content=$piece}})}|ConvertTo-Json -Depth 8 -Compress
                    $bytes=[Text.Encoding]::UTF8.GetBytes("data: $chunk`n`n")
                    $response.OutputStream.Write($bytes,0,$bytes.Length);$response.OutputStream.Flush()
                }
                $last=@{id='packaged-fixture';model='deepseek-flash';choices=@(@{index=0;delta=@{};finish_reason='stop'});usage=$usage}|ConvertTo-Json -Depth 8 -Compress
                $bytes=[Text.Encoding]::UTF8.GetBytes("data: $last`n`ndata: [DONE]`n`n")
                $response.OutputStream.Write($bytes,0,$bytes.Length);$response.OutputStream.Flush()
            }else{
                $response.ContentType='application/json'
                $body=@{id='packaged-fixture';model='deepseek-flash';choices=@(@{index=0;message=@{role='assistant';content=$content};finish_reason='stop'});usage=$usage}|ConvertTo-Json -Depth 8 -Compress
                $bytes=[Text.Encoding]::UTF8.GetBytes($body)
                $response.OutputStream.Write($bytes,0,$bytes.Length)
            }
        }finally{$response.Close()}
    }
}finally{$listener.Stop();$listener.Close()}
