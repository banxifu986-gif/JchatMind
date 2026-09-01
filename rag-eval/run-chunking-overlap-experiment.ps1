param(
    [switch]$SummaryOnly
)

$ErrorActionPreference = "Stop"

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $repositoryRoot "backend_v2"
$outputRoot = Join-Path $backendRoot "target\rag-eval\chunking-overlap"
$mavenCommand = "mvn.cmd"
$isolatedJdbcUrl = "jdbc:postgresql://127.0.0.1:55432/jchatmind_rag_eval"
$corpusPath = Join-Path $backendRoot "src\test\resources\rag-eval\fixtures\rag-chunking-experiment.md"
$corpusSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $corpusPath).Hash.ToLowerInvariant()
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null

$variants = @(
    [ordered]@{ name = "structure-unlimited"; maxChars = 0; overlapChars = 0 },
    [ordered]@{ name = "1000-no-overlap"; maxChars = 1000; overlapChars = 0 },
    [ordered]@{ name = "1000-overlap-10pct"; maxChars = 1000; overlapChars = 100 },
    [ordered]@{ name = "1500-no-overlap"; maxChars = 1500; overlapChars = 0 },
    [ordered]@{ name = "1500-overlap-10pct"; maxChars = 1500; overlapChars = 150 },
    [ordered]@{ name = "2000-no-overlap"; maxChars = 2000; overlapChars = 0 },
    [ordered]@{ name = "2000-overlap-10pct"; maxChars = 2000; overlapChars = 200 },
    [ordered]@{ name = "3000-no-overlap"; maxChars = 3000; overlapChars = 0 },
    [ordered]@{ name = "3000-overlap-10pct"; maxChars = 3000; overlapChars = 300 }
)

function Read-VariantResult($variant) {
    $reportPath = Join-Path $outputRoot "$($variant.name)-report.json"
    $statsPath = Join-Path $outputRoot "$($variant.name)-stats.json"
    $report = Get-Content -Raw $reportPath | ConvertFrom-Json
    $summary = $report | Select-Object -First 1
    $stats = Get-Content -Raw $statsPath | ConvertFrom-Json
    $contentRewrite = $summary.breakdown | Where-Object { $_.group -eq "fixture/content_rewrite" } | Select-Object -First 1
    $boundaryRewrite = $summary.breakdown | Where-Object { $_.group -eq "fixture/boundary_rewrite" } | Select-Object -First 1
    [PSCustomObject]@{
        experimentVersion = "chunking-overlap-v1"
        variant = $variant.name
        corpusSha256 = $corpusSha256
        embeddingModel = "bge-m3:latest"
        queryMode = "content_rewrite+boundary_rewrite"
        maxChars = $variant.maxChars
        overlapChars = $variant.overlapChars
        totalChunkCount = $stats.totalChunkCount
        totalChunkCharacters = $stats.totalChunkCharacters
        maxChunkCharacters = $stats.maxChunkCharacters
        overlapPairs = $stats.overlapPairs
        observedOverlapCharacters = $stats.observedOverlapCharacters
        overlapCharacterRatio = if ($stats.totalChunkCharacters -gt 0) {
            [math]::Round($stats.observedOverlapCharacters / $stats.totalChunkCharacters, 4)
        } else {
            0
        }
        contentRewriteEvaluated = $contentRewrite.evaluated
        contentRewriteRecallAt1 = $contentRewrite.recallAt1
        contentRewriteRecallAt5 = $contentRewrite.recallAt5
        contentRewriteMrrAt10 = $contentRewrite.mrrAt10
        contentRewriteContextPrecisionAt5 = $contentRewrite.contextPrecisionAt5
        contentRewriteContextRecallAt5 = $contentRewrite.contextRecallAt5
        boundaryRewriteEvaluated = if ($boundaryRewrite -and $boundaryRewrite.evaluated -gt 0) { $boundaryRewrite.evaluated } else { $null }
        boundaryRewriteRecallAt1 = if ($boundaryRewrite -and $boundaryRewrite.evaluated -gt 0) { $boundaryRewrite.recallAt1 } else { $null }
        boundaryRewriteRecallAt5 = if ($boundaryRewrite -and $boundaryRewrite.evaluated -gt 0) { $boundaryRewrite.recallAt5 } else { $null }
        boundaryRewriteMrrAt10 = if ($boundaryRewrite -and $boundaryRewrite.evaluated -gt 0) { $boundaryRewrite.mrrAt10 } else { $null }
        boundaryRewriteContextPrecisionAt5 = if ($boundaryRewrite -and $boundaryRewrite.evaluated -gt 0) { $boundaryRewrite.contextPrecisionAt5 } else { $null }
        boundaryRewriteContextRecallAt5 = if ($boundaryRewrite -and $boundaryRewrite.evaluated -gt 0) { $boundaryRewrite.contextRecallAt5 } else { $null }
        reportPath = $reportPath
    }
}

if (-not $SummaryOnly) {
    Push-Location $backendRoot
    try {
        foreach ($variant in $variants) {
            $reportPath = Join-Path $outputRoot "$($variant.name)-report.json"
            $statsPath = Join-Path $outputRoot "$($variant.name)-stats.json"

            Write-Host "Running $($variant.name): maxChars=$($variant.maxChars), overlapChars=$($variant.overlapChars)"
            & $mavenCommand -q `
        "-Dtest=RagRecallEvaluationTest" `
        "-Dspring.datasource.url=$isolatedJdbcUrl" `
        "-Dspring.datasource.username=rag_eval" `
        "-Dspring.datasource.password=" `
        "-Drag.eval.runtime.enabled=true" `
        "-Drag.eval.mode=fixture" `
        "-Drag.eval.fixture.only-chunking=true" `
        "-Drag.eval.fixture.multi-doc=false" `
        "-Drag.eval.chunking.boundary-cases=true" `
        "-Drag.eval.chunking.quick=true" `
        "-Drag.eval.chunking.max-section-content-length=$($variant.maxChars)" `
        "-Drag.eval.chunking.overlap-length=$($variant.overlapChars)" `
        "-Drag.eval.chunking.stats-path=$statsPath" `
        "-Drag.eval.report-path=$reportPath" `
        "-Drag.eval.enable-ab-comparison=false" `
                test 2>&1 | ForEach-Object { Write-Host $_ }
            if ($LASTEXITCODE -ne 0) {
                throw "Chunking experiment failed: $($variant.name), exitCode=$LASTEXITCODE"
            }
        }
    }
    finally {
        Pop-Location
    }
}

$results = foreach ($variant in $variants) {
    Read-VariantResult $variant
}

$resultPath = Join-Path $outputRoot "summary.json"
$results | ConvertTo-Json -Depth 8 | Set-Content -Path $resultPath -Encoding utf8

Write-Host ""
Write-Host "Chunking/overlap experiment completed. Summary: $resultPath"
$results | Select-Object variant, maxChars, overlapChars, totalChunkCount, overlapCharacterRatio, contentRewriteRecallAt5, boundaryRewriteRecallAt5, contentRewriteMrrAt10, boundaryRewriteMrrAt10 | Format-Table -AutoSize
