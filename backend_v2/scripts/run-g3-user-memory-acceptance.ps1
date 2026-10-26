param([ValidateRange(1, 8)][int]$DeepSeekCallLimit = 8)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$backendRoot = Join-Path $repoRoot "backend_v2"
$uiRoot = Join-Path $repoRoot "ui"
$targetRoot = Join-Path $backendRoot "target/g3-user-memory-acceptance-$([guid]::NewGuid().ToString('N'))"
$schemaPath = Join-Path $backendRoot "src/test/resources/g3-user-memory-acceptance-schema.sql"
$statePath = Join-Path $targetRoot "state.json"
$playwrightResultsPath = Join-Path $targetRoot "playwright-results"
$backendConfigPath = Join-Path $targetRoot "application.properties"
$proxyPath = Join-Path $targetRoot "deepseek-counting-proxy.mjs"
$backendLogPath = Join-Path $targetRoot "backend.log"
$backendErrorLogPath = Join-Path $targetRoot "backend-error.log"
$viteLogPath = Join-Path $targetRoot "vite.log"
$viteErrorLogPath = Join-Path $targetRoot "vite-error.log"
$proxyLogPath = Join-Path $targetRoot "proxy.log"
$proxyErrorLogPath = Join-Path $targetRoot "proxy-error.log"

$postgresName = "g3-memory-pg-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
$redisName = "g3-memory-redis-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
$rabbitName = "g3-memory-rabbit-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
$postgresPort = 55474
$redisPort = 55475
$rabbitPort = 55476
$backendPort = 18080
$vitePort = 5174
$proxyPort = 18090
$backendProcess = $null
$viteProcess = $null
$proxyProcess = $null
$previousEnvironment = @{}

function Invoke-Docker {
    param([string[]]$Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker 命令失败（exit=$LASTEXITCODE）：docker $($Arguments[0])"
    }
}

function Wait-HttpReady {
    param([string]$Url, [int]$ExpectedStatus, [System.Diagnostics.Process]$Process)
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    $lastStatus = "no response"
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process -and $Process.HasExited) {
            throw "进程在就绪前退出，exit=$($Process.ExitCode)"
        }
        try {
            $response = Invoke-WebRequest -Uri $Url -TimeoutSec 2 -SkipHttpErrorCheck
            $lastStatus = [int]$response.StatusCode
            if (($ExpectedStatus -eq 0 -and $lastStatus -ge 200 -and $lastStatus -lt 500) -or
                $lastStatus -eq $ExpectedStatus) { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "服务未就绪：$Url，最后 HTTP 状态=$lastStatus"
}

function Start-Backend {
    $jarPath = Join-Path $backendRoot "target/jchatmind-0.0.1-SNAPSHOT.jar"
    $javaPath = "java.exe"
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $javaPath = Join-Path $env:JAVA_HOME "bin/java.exe"
    }
    $priorDeepSeekKey = [Environment]::GetEnvironmentVariable("DEEPSEEK_API_KEY", "Process")
    $priorSpringDeepSeekKey = [Environment]::GetEnvironmentVariable("SPRING_AI_DEEPSEEK_API_KEY", "Process")
    try {
        $env:DEEPSEEK_API_KEY = $deepSeekKey
        $env:SPRING_AI_DEEPSEEK_API_KEY = $deepSeekKey
        Start-Process -FilePath $javaPath -ArgumentList @(
            "-Dspring.devtools.restart.enabled=false",
            "-jar", $jarPath,
            "--spring.config.additional-location=file:$backendConfigPath",
            "--server.address=127.0.0.1",
            "--server.port=$backendPort"
        ) -WorkingDirectory $backendRoot -WindowStyle Hidden -PassThru `
          -RedirectStandardOutput $backendLogPath -RedirectStandardError $backendErrorLogPath
    } finally {
        if ($null -eq $priorDeepSeekKey) { Remove-Item Env:DEEPSEEK_API_KEY -ErrorAction SilentlyContinue }
        else { $env:DEEPSEEK_API_KEY = $priorDeepSeekKey }
        if ($null -eq $priorSpringDeepSeekKey) { Remove-Item Env:SPRING_AI_DEEPSEEK_API_KEY -ErrorAction SilentlyContinue }
        else { $env:SPRING_AI_DEEPSEEK_API_KEY = $priorSpringDeepSeekKey }
    }
}

function Invoke-UiPhase {
    param([string]$Phase)
    $env:G3_MEMORY_PHASE = $Phase
    $playwrightCli = Join-Path $uiRoot "node_modules/@playwright/test/cli.js"
    Push-Location $uiRoot
    try {
        & node $playwrightCli test tests/g3-user-memory-preference-live.spec.ts --project=edge --workers=1 `
            --reporter=list --output=$playwrightResultsPath
        if ($LASTEXITCODE -ne 0) { throw "Playwright $Phase 阶段失败（exit=$LASTEXITCODE）" }
    } finally {
        Pop-Location
        Remove-Item Env:G3_MEMORY_PHASE -ErrorAction SilentlyContinue
    }
}

function Assert-ModelGate {
    $stats = Invoke-RestMethod -Uri "http://127.0.0.1:$proxyPort/__stats" -TimeoutSec 5
    $failedStatuses = @($stats.statuses.PSObject.Properties.Name | Where-Object { [int]$_ -ge 400 })
    $fallbackPattern = "falling back to keyword-based|No ChatClient available|LLM memory extraction failed"
    $fallbackDetected = (Test-Path -LiteralPath $backendLogPath) -and
        (Select-String -LiteralPath $backendLogPath -Pattern $fallbackPattern -Quiet)
    $fallbackDetected = $fallbackDetected -or ((Test-Path -LiteralPath $backendErrorLogPath) -and
        (Select-String -LiteralPath $backendErrorLogPath -Pattern $fallbackPattern -Quiet))
    if ($stats.attempts -gt $DeepSeekCallLimit -or $stats.calls -gt $DeepSeekCallLimit -or
        $failedStatuses.Count -gt 0 -or $fallbackDetected) {
        throw "DeepSeek 请求失败、达到调用上限或记忆提取进入 fallback。"
    }
    return $stats
}

function Stop-ProcessQuietly {
    param($Process)
    if ($Process -and -not $Process.HasExited) {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
        Wait-Process -Id $Process.Id -Timeout 10 -ErrorAction SilentlyContinue
    }
}

$deepSeekKey = [Environment]::GetEnvironmentVariable("DEEPSEEK_API_KEY", "User")
if ([string]::IsNullOrWhiteSpace($deepSeekKey)) {
    throw "Windows 用户级 DEEPSEEK_API_KEY 未设置。"
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "未找到 docker 命令。" }
if (-not (Test-Path -LiteralPath (Join-Path $uiRoot "node_modules/@playwright/test/cli.js"))) {
    throw "UI Playwright 依赖未安装。"
}
if (-not (Test-Path -LiteralPath $schemaPath)) { throw "隔离 schema 文件不存在。" }
$reservedPorts = @($postgresPort, $redisPort, $rabbitPort, $backendPort, $vitePort, $proxyPort)
$occupiedPorts = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $reservedPorts -contains $_.LocalPort } |
    Select-Object -ExpandProperty LocalPort -Unique)
if ($occupiedPorts.Count -gt 0) { throw "验收端口已被占用：$($occupiedPorts -join ',')" }

New-Item -ItemType Directory -Path $targetRoot -Force | Out-Null
foreach ($name in @("DEEPSEEK_API_KEY", "SPRING_AI_DEEPSEEK_API_KEY", "G3_MEMORY_LIVE", "G3_API_BASE_URL", "G3_STATE_FILE", "G1_UI_BASE_URL", "VITE_API_BASE_URL", "VITE_SSE_BASE_URL")) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

try {
    $env:G3_MEMORY_LIVE = "true"
    $env:G3_API_BASE_URL = "http://127.0.0.1:$backendPort/api"
    $env:G3_STATE_FILE = $statePath
    $env:G1_UI_BASE_URL = "http://127.0.0.1:$vitePort"
    $env:VITE_API_BASE_URL = "http://127.0.0.1:$backendPort/api"
    $env:VITE_SSE_BASE_URL = "http://127.0.0.1:$backendPort/sse"

    $proxySource = @'
import http from "node:http";
import https from "node:https";
let attempts = 0;
let calls = 0;
const statuses = {};
const errors = [];
const CALL_LIMIT = __DEEPSEEK_CALL_LIMIT__;
http.createServer(async (request, response) => {
  if (request.url === "/__stats") {
    response.setHeader("content-type", "application/json");
    response.end(JSON.stringify({ attempts, calls, statuses, errors }));
    return;
  }
  attempts += 1;
  if (attempts > CALL_LIMIT) {
    response.writeHead(429, { "content-type": "application/json" });
    response.end('{"error":"acceptance call limit exceeded"}');
    statuses["429"] = (statuses["429"] || 0) + 1;
    return;
  }
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  const headers = {};
  for (const [name, value] of Object.entries(request.headers)) {
    if (!["host", "connection", "content-length", "transfer-encoding", "expect"].includes(name.toLowerCase()) && value) {
      headers[name] = Array.isArray(value) ? value.join(",") : value;
    }
  }
  try {
    const upstream = https.request({
      hostname: "api.deepseek.com",
      port: 443,
      path: request.url,
      method: request.method,
      headers,
      timeout: 30_000,
    });
    upstream.setTimeout(30_000, () => upstream.destroy(new Error("upstream timeout")));
    upstream.on("response", (upstreamResponse) => {
      const status = upstreamResponse.statusCode || 502;
      statuses[String(status)] = (statuses[String(status)] || 0) + 1;
      const responseHeaders = {};
      for (const [name, value] of Object.entries(upstreamResponse.headers)) {
        if (!["connection", "transfer-encoding", "keep-alive", "upgrade"].includes(name.toLowerCase()) && value) {
          responseHeaders[name] = value;
        }
      }
      response.writeHead(status, responseHeaders);
      upstreamResponse.pipe(response);
    });
    upstream.on("error", (error) => {
      errors.push({ name: error?.name ?? "Error", code: error?.code ?? "" });
      statuses["502"] = (statuses["502"] || 0) + 1;
      if (!response.headersSent) response.writeHead(502, { "content-type": "application/json" });
      response.end('{"error":"upstream unavailable"}');
    });
    calls += 1;
    upstream.end(chunks.length ? Buffer.concat(chunks) : undefined);
  } catch (error) {
    errors.push({ name: error?.name ?? "Error", code: error?.cause?.code ?? "" });
    statuses["502"] = (statuses["502"] || 0) + 1;
    response.writeHead(502, { "content-type": "application/json" });
    response.end('{"error":"upstream unavailable"}');
  }
}).listen(18090, "127.0.0.1");
'@
    $proxySource = $proxySource.Replace("__DEEPSEEK_CALL_LIMIT__", [string]$DeepSeekCallLimit)
    Set-Content -LiteralPath $proxyPath -Value $proxySource -Encoding utf8

    $schema = Get-Content -LiteralPath $schemaPath -Raw
    $schemaHash = (Get-FileHash -LiteralPath $schemaPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $schemaLines = (Get-Content -LiteralPath $schemaPath).Count
    if ($schemaLines -gt 250 -or $schema -match '(?im)^\s*(INSERT|COPY)\b') {
        throw "隔离 schema 超过范围或含数据导入语句。"
    }

    Invoke-Docker @("run", "--detach", "--rm", "--name", $postgresName,
        "--publish", "127.0.0.1:${postgresPort}:5432",
        "--env", "POSTGRES_DB=g3_user_memory",
        "--env", "POSTGRES_USER=g3_user_memory",
        "--env", "POSTGRES_HOST_AUTH_METHOD=trust",
        "jchatmind-postgres-vchord-bm25:0.3.0") | Out-Null
    Invoke-Docker @("run", "--detach", "--rm", "--name", $redisName,
        "--publish", "127.0.0.1:${redisPort}:6379", "redis:7-alpine") | Out-Null
    Invoke-Docker @("run", "--detach", "--rm", "--name", $rabbitName,
        "--publish", "127.0.0.1:${rabbitPort}:5672", "rabbitmq:3-management-alpine") | Out-Null

    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        & docker exec $postgresName pg_isready -U g3_user_memory -d g3_user_memory *> $null
        if ($LASTEXITCODE -eq 0) { break }
        if ([DateTime]::UtcNow -ge $deadline) { throw "隔离 PostgreSQL 未就绪。" }
        Start-Sleep -Milliseconds 500
    } while ($true)
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        & docker exec $redisName redis-cli ping *> $null
        if ($LASTEXITCODE -eq 0) { break }
        if ([DateTime]::UtcNow -ge $deadline) { throw "隔离 Redis 未就绪。" }
        Start-Sleep -Milliseconds 500
    } while ($true)
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        $rabbitRunning = & docker inspect --format '{{.State.Running}}' $rabbitName 2>$null
        if ($LASTEXITCODE -eq 0 -and $rabbitRunning.Trim() -eq "true") { break }
        if ([DateTime]::UtcNow -ge $deadline) { throw "隔离 RabbitMQ 容器未运行。" }
        Start-Sleep -Milliseconds 500
    } while ($true)
    Get-Content -LiteralPath $schemaPath -Raw | & docker exec -i $postgresName psql -v ON_ERROR_STOP=1 -U g3_user_memory -d g3_user_memory
    if ($LASTEXITCODE -ne 0) { throw "隔离 schema 初始化失败。" }

    $properties = @(
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:$postgresPort/g3_user_memory",
        "spring.datasource.username=g3_user_memory",
        "spring.datasource.password=",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=$redisPort",
        "spring.rabbitmq.host=127.0.0.1",
        "spring.rabbitmq.port=$rabbitPort",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "spring.rabbitmq.dynamic=false",
        "agent.execution.queue.enabled=true",
        'spring.ai.deepseek.api-key=${DEEPSEEK_API_KEY}',
        "spring.ai.deepseek.base-url=http://127.0.0.1:$proxyPort",
        "spring.ai.deepseek.chat.options.model=deepseek-chat",
        "spring.ai.retry.max-attempts=1",
        "spring.ai.zhipuai.api-key=acceptance-disabled",
        "spring.ai.model.embedding=none",
        "spring.ai.model.image=none",
        "spring.ai.mcp.client.enabled=false",
        "spring.ai.mcp.server.enabled=false",
        "spring.mail.host=127.0.0.1",
        "spring.mail.username=g3-acceptance-disabled",
        "mybatis.mapper-locations=classpath*:mapper/*.xml",
        "mybatis.type-handlers-package=com.kama.jchatmind.typehandler",
        "ollama.base-url=http://127.0.0.1:55454",
        "ollama.embedding-model=g3-acceptance-disabled",
        "document.storage.base-path=target/g3-user-memory-acceptance/documents",
        "jwt.secret=g3-isolated-$(New-Guid)-$(New-Guid)",
        "jwt.expiration=3600",
        "logging.level.root=WARN"
    )
    Set-Content -LiteralPath $backendConfigPath -Value $properties -Encoding utf8

    $maven = Join-Path $backendRoot "mvnw.cmd"
    Push-Location $backendRoot
    try {
        & $maven -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw "后端打包失败（exit=$LASTEXITCODE）。" }
    } finally { Pop-Location }

    $proxyProcess = Start-Process -FilePath "node.exe" -ArgumentList @($proxyPath) -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $proxyLogPath -RedirectStandardError $proxyErrorLogPath
    $backendProcess = Start-Backend
    Wait-HttpReady "http://127.0.0.1:$backendPort/api/users/whoami" 0 $backendProcess

    $viteCli = Join-Path $uiRoot "node_modules/vite/bin/vite.js"
    $viteProcess = Start-Process -FilePath "node.exe" -ArgumentList @($viteCli, "--host", "127.0.0.1", "--port", "$vitePort", "--strictPort") `
        -WorkingDirectory $uiRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $viteLogPath -RedirectStandardError $viteErrorLogPath
    Wait-HttpReady "http://127.0.0.1:$vitePort" 200 $viteProcess

    Write-Output "隔离库已就绪；schema SHA-256=$schemaHash（不含业务数据）。"
    Invoke-UiPhase "prepare"
    if (-not (Test-Path -LiteralPath $statePath)) { throw "偏好候选阶段未生成验收状态。" }
    $stats = Assert-ModelGate
    Write-Output "候选生成通过门禁；DeepSeek 转发数=$($stats.calls)，错误状态=0，fallback=0。"

    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $evidenceCheck = "SELECT EXISTS (SELECT 1 FROM user_memory_candidate c JOIN chat_message m ON m.id=c.evidence_message_id WHERE c.id='$($state.preferenceCandidateId)'::uuid AND c.session_id=m.session_id AND c.user_id='$($state.userId)')"
    $evidenceResult = & docker exec $postgresName psql -At -U g3_user_memory -d g3_user_memory -c $evidenceCheck
    if ($LASTEXITCODE -ne 0 -or $evidenceResult.Trim() -ne "t") { throw "候选证据未关联到来源用户消息。" }
    Invoke-UiPhase "confirm"
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if ([string]::IsNullOrWhiteSpace($state.preferenceMemoryId)) { throw "确认候选后未发现长期记忆 ID。" }
    Write-Output "候选证据消息关联已确认，UI 确认后 candidate=0、memory=1；开始仅重启测试后端 JVM。"

    Stop-ProcessQuietly $backendProcess
    $backendProcess = Start-Backend
    Wait-HttpReady "http://127.0.0.1:$backendPort/api/users/whoami" 0 $backendProcess
    Invoke-UiPhase "resume"

    $stats = Assert-ModelGate

    Write-Output "G3 用户记忆跨 JVM 重启验收通过；DeepSeek 请求数=$($stats.calls)，状态码均成功。"
} catch {
    try {
        $failureStats = Invoke-RestMethod -Uri "http://127.0.0.1:$proxyPort/__stats" -TimeoutSec 2
        $statusSummary = ($failureStats.statuses.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ","
        $errorSummary = @($failureStats.errors | ForEach-Object { if ($_.code) { $_.code } else { $_.name } } | Select-Object -Unique) -join ","
        Write-Output "验收未通过；DeepSeek 转发=$($failureStats.calls)，尝试=$($failureStats.attempts)，状态=$statusSummary，传输错误=$errorSummary。"
    } catch { }
    if ((Test-Path -LiteralPath $backendLogPath) -and
        ((Select-String -LiteralPath $backendLogPath -Pattern "falling back to keyword-based|No ChatClient available|LLM memory extraction failed" -Quiet) -or
         (Test-Path -LiteralPath $backendErrorLogPath) -and
         (Select-String -LiteralPath $backendErrorLogPath -Pattern "falling back to keyword-based|No ChatClient available|LLM memory extraction failed" -Quiet))) {
        Write-Output "检测到记忆提取 fallback。"
    }
    throw
} finally {
    Stop-ProcessQuietly $backendProcess
    Stop-ProcessQuietly $viteProcess
    Stop-ProcessQuietly $proxyProcess
    foreach ($containerName in @($rabbitName, $redisName, $postgresName)) {
        & docker rm --force $containerName *> $null
    }
    foreach ($name in $previousEnvironment.Keys) {
        if ($null -eq $previousEnvironment[$name]) {
            Remove-Item "Env:$name" -ErrorAction SilentlyContinue
        } else {
            Set-Item "Env:$name" $previousEnvironment[$name]
        }
    }
    $cleanupRoot = [IO.Path]::GetFullPath($targetRoot)
    $targetParent = [IO.Path]::GetFullPath((Join-Path $backendRoot "target")) + [IO.Path]::DirectorySeparatorChar
    if ($cleanupRoot.StartsWith($targetParent, [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($cleanupRoot).StartsWith("g3-user-memory-acceptance-", [StringComparison]::Ordinal)) {
        if (Test-Path -LiteralPath $cleanupRoot) { Remove-Item -LiteralPath $cleanupRoot -Recurse -Force }
    }
    if ($null -ne $deepSeekKey) { $deepSeekKey = $null }
}
