$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    $scripts = @(Get-ChildItem -LiteralPath tests -Filter '*.test.cjs' | ForEach-Object FullName)
    & node --test @scripts
    if ($LASTEXITCODE -ne 0) { throw 'JavaScript tests failed' }
    $classes = Join-Path ([IO.Path]::GetTempPath()) ('freepocket-tests-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $classes | Out-Null
    & javac --release 17 -encoding UTF-8 -d $classes app/src/main/java/cn/run/freepocket/EpicEntitlements.java tests/java/EpicEntitlementsTest.java
    if ($LASTEXITCODE -ne 0) { throw 'Java ownership test compilation failed' }
    & java -cp $classes cn.run.freepocket.EpicEntitlementsTest
    if ($LASTEXITCODE -ne 0) { throw 'Java ownership tests failed' }
} finally { Pop-Location }
