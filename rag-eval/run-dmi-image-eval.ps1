# dmi-image-rag-v1 真实模型评测入口
# 用法: pwsh rag-eval/run-dmi-image-eval.ps1
# 前置: docker compose -f docker-compose.rag-eval.yml up -d; docker compose up -d ollama
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot "backend_v2\.env"
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
            $name, $value = $line -split "=", 2
            [Environment]::SetEnvironmentVariable($name.Trim(), $value, "Process")
        }
    }
}
if (-not [Environment]::GetEnvironmentVariable("SPRING_AI_DEEPSEEK_API_KEY", "Process")) {
    $deepSeekName = Get-ChildItem env: | Where-Object { $_.Name -match "DEEPSEEK" -and $_.Name -match "API" } | Select-Object -First 1
    if ($deepSeekName) {
        [Environment]::SetEnvironmentVariable("SPRING_AI_DEEPSEEK_API_KEY", $deepSeekName.Value, "Process")
    } else {
        Write-Host "警告: .env 中未发现 DeepSeek API key，抽取将全部失败并记入报告。"
    }
}

Push-Location (Join-Path $repoRoot "backend_v2")
try {
    .\mvnw.cmd test "-Dtest=DmiImageRagEvalL2Test" "-Ddmi.image-rag.eval.enabled=true"
} finally {
    Pop-Location
}
Write-Host "报告: backend_v2/target/rag-eval/dmi/dmi-image-rag-v1-runtime.json"