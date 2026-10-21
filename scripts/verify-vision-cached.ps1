param([string]$DependencyCache)

# Fallback when Gradle plugins are unavailable. Requires an existing project build and local dependency jars.
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$output = Join-Path $workspace 'build/vision-verification/main'
$classpathFile = Join-Path $workspace 'build/moddev/clientLegacyClasspath.txt'
$existingClasses = Join-Path $workspace 'build/classes/java/main'
if (!(Test-Path -LiteralPath $classpathFile) -or !(Test-Path -LiteralPath $existingClasses)) {
    throw 'Run a normal Gradle build first; this fallback verifies changed sources against its cached classes.'
}

$legacyPaths = @(Get-Content -LiteralPath $classpathFile | Where-Object { Test-Path -LiteralPath $_ })
if (!$DependencyCache) {
    $cachedPath = $legacyPaths | Where-Object { $_ -match '[\\/]caches[\\/]modules-2[\\/]files-2\.1[\\/]' } | Select-Object -First 1
    if (!$cachedPath) { throw 'Cannot locate the dependency cache; specify -DependencyCache.' }
    $DependencyCache = [regex]::Match($cachedPath, '^.*?[\\/]caches[\\/]modules-2[\\/]files-2\.1').Value
}
$artifacts = @(Get-ChildItem -LiteralPath (Join-Path $workspace 'build/moddev/artifacts') -Filter '*.jar' |
    Where-Object { $_.Name -notmatch 'sources|resources' } | ForEach-Object { $_.FullName })
$cachedJars = @(Get-ChildItem -LiteralPath $DependencyCache -Filter '*.jar' -Recurse |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } | ForEach-Object { $_.FullName })
$classpath = (@($existingClasses) + $artifacts + $legacyPaths + $cachedJars) -join [IO.Path]::PathSeparator
New-Item -ItemType Directory -Path $output -Force | Out-Null

function ConvertTo-JavaArgument([string]$Value) {
    return '"' + $Value.Replace('\', '/').Replace('"', '\"') + '"'
}

$sourceRoot = Join-Path $workspace 'src/main/java/net/rev/stealthandalert'
$sources = @(
    'util/BodyExposure.java', 'util/BodyVisibility.java', 'util/DetectionMath.java',
    'util/CommonUtils.java', 'util/StealthEngine.java', 'util/ConeRaycaster.java',
    'client/heatmap/HeatmapComputer.java', 'client/renderer/SurfaceVisionFusion.java',
    'client/renderer/VisionConeRenderer.java'
) | ForEach-Object { Join-Path $sourceRoot $_ }
$sources += Join-Path $workspace 'src/test/java/net/rev/stealthandalert/client/renderer/VisionRegressionTests.java'
$compilerArguments = @('-encoding', 'UTF-8', '-proc:none', '-d', $output, '-classpath', $classpath) + $sources
$compilerFile = Join-Path $workspace 'build/vision-verification/cached-javac.args'
[IO.File]::WriteAllLines($compilerFile, @($compilerArguments | ForEach-Object { ConvertTo-JavaArgument $_ }),
    [Text.UTF8Encoding]::new($false))
& javac "@$compilerFile"
if ($LASTEXITCODE -ne 0) { throw 'Changed-source compilation failed.' }

$runtimeFile = Join-Path $workspace 'build/vision-verification/cached-java.args'
$runtimeArguments = @('-Djava.awt.headless=true', '-cp', ($output + [IO.Path]::PathSeparator + $classpath),
    'net.rev.stealthandalert.client.renderer.VisionRegressionTests')
[IO.File]::WriteAllLines($runtimeFile, @($runtimeArguments | ForEach-Object { ConvertTo-JavaArgument $_ }),
    [Text.UTF8Encoding]::new($false))
& java "@$runtimeFile"
if ($LASTEXITCODE -ne 0) { throw 'Vision regression checks failed.' }
Write-Output 'Changed-source compilation and vision regressions passed; this does not replace a full Gradle build.'
