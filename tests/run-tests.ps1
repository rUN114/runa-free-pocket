param([switch]$Browser)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    $scripts = @(Get-ChildItem -LiteralPath tests -Filter '*.test.cjs' | ForEach-Object FullName)
    & node --test @scripts
    if ($LASTEXITCODE -ne 0) { throw 'JavaScript tests failed' }
    if ($Browser) {
        & node tests/browser/ui.cjs
        if ($LASTEXITCODE -ne 0) { throw 'Browser UI tests failed' }
    }
    # 原生拥有凭证分页检查已迁入 Gradle 单元测试：app/src/test/.../EpicEntitlementsTest.kt，
    # 随 `./gradlew.bat testReleaseUnitTest` 一起运行，不再单独编译 Java 测试类。
} finally { Pop-Location }
