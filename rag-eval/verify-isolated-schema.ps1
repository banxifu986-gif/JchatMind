param(
    [string]$ContainerName = "jchatmind-rag-eval-postgres"
)

$ErrorActionPreference = "Stop"

function Invoke-EvaluationQuery {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Query
    )

    $result = & docker exec $ContainerName psql -U rag_eval -d jchatmind_rag_eval -v ON_ERROR_STOP=1 -Atc $Query
    if ($LASTEXITCODE -ne 0) {
        throw "Evaluation database query failed."
    }
    return @($result | Where-Object { $_ -ne "" })
}

$expectedRelations = @(
    "rag_eval.evaluation_namespace",
    "public.knowledge_base",
    "public.document",
    "public.chunk_bge_m3"
)
$actualRelations = Invoke-EvaluationQuery @"
SELECT table_schema || '.' || table_name
FROM information_schema.tables
WHERE (table_schema, table_name) IN (
    ('rag_eval', 'evaluation_namespace'),
    ('public', 'knowledge_base'),
    ('public', 'document'),
    ('public', 'chunk_bge_m3')
)
ORDER BY table_schema, table_name;
"@
if ((@($actualRelations) -join ",") -ne (@($expectedRelations | Sort-Object) -join ",")) {
    throw "Unexpected evaluation relations: $($actualRelations -join ', ')"
}

$namespace = Invoke-EvaluationQuery "SELECT namespace FROM rag_eval.evaluation_namespace;"
if (@($namespace) -join "," -ne "rag-eval") {
    throw "The isolated database must contain exactly the rag-eval namespace."
}

$namespaceColumns = Invoke-EvaluationQuery @"
SELECT table_name || ':' || is_nullable || ':' || column_default
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name IN ('knowledge_base', 'document', 'chunk_bge_m3')
  AND column_name = 'evaluation_namespace'
ORDER BY table_name;
"@
$invalidNamespaceColumns = @($namespaceColumns | Where-Object {
        $_ -notmatch ':NO:' -or $_ -notmatch "rag-eval"
    })
if (@($namespaceColumns).Count -ne 3 -or $invalidNamespaceColumns.Count -ne 0) {
    throw "KB, document, and chunk records must default to the rag-eval namespace."
}

$vectorType = Invoke-EvaluationQuery @"
SELECT format_type(a.atttypid, a.atttypmod)
FROM pg_attribute a
WHERE a.attrelid = 'public.chunk_bge_m3'::regclass
  AND a.attname = 'embedding'
  AND NOT a.attisdropped;
"@
if (@($vectorType) -join "," -ne "vector(1024)") {
    throw "chunk_bge_m3.embedding must be vector(1024)."
}

$bm25Indexes = Invoke-EvaluationQuery @"
SELECT indexname
FROM pg_indexes
WHERE schemaname = 'public'
  AND indexname IN ('idx_chunk_bge_m3_title_bm25', 'idx_chunk_bge_m3_content_bm25')
ORDER BY indexname;
"@
if (@($bm25Indexes) -join "," -ne "idx_chunk_bge_m3_content_bm25,idx_chunk_bge_m3_title_bm25") {
    throw "Both VectorChord BM25 indexes are required."
}

Write-Output "Isolated evaluation schema is ready."
