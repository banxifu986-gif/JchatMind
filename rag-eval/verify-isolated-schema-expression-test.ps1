$ErrorActionPreference = "Stop"

$scriptPath = Join-Path $PSScriptRoot "verify-isolated-schema.ps1"
$script = Get-Content -Raw -LiteralPath $scriptPath
$expectedCondition = 'if ((@($actualRelations) -join ",") -ne (@($expectedRelations | Sort-Object) -join ","))'
$expectedInvalidColumnsAssignment = '$invalidNamespaceColumns = @($namespaceColumns | Where-Object {'
$expectedNamespaceCondition = 'if (@($namespaceColumns).Count -ne 3 -or $invalidNamespaceColumns.Count -ne 0) {'

if (-not $script.Contains($expectedCondition)) {
    throw "Relation comparison must compare the two joined strings."
}

if (-not $script.Contains($expectedInvalidColumnsAssignment) -or -not $script.Contains($expectedNamespaceCondition)) {
    throw "Namespace column validation must evaluate invalid columns before combining conditions."
}

Write-Output "Relation comparison expression is unambiguous."
