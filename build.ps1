# 编译 NWNDungeon：javac -> jar。产物在 dist\nwndungeon-<plugin.yml 里的版本>.jar
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSCommandPath
$jdk = 'C:\Program Files\Java\jdk-21\bin'
# 编译依赖：项目自己的 lib\ 与本机 simpfun-ops\opsbridge\lib 一起进 classpath
# （paper-api.jar 在本机目录里；PlaceholderAPI.jar 等可选依赖放项目 lib\）
$libDir = "$root\lib"
$libFallback = 'C:\Users\ASUS\Documents\Projects\simpfun-ops\opsbridge\lib'
$classes = Join-Path $root 'build\classes'
$dist = Join-Path $root 'dist'

if (-not (Test-Path (Join-Path $libFallback 'paper-api.jar')) -and -not (Test-Path (Join-Path $libDir 'paper-api.jar'))) {
    throw "缺少编译依赖：把 paper-api.jar 放进 $libDir"
}

$version = ((Get-Content (Join-Path $root 'plugin.yml') | Where-Object { $_ -match '^version:' }) -split ':', 2)[1].Trim()

Remove-Item -LiteralPath $classes -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $dist | Out-Null

$jars = @()
$jars += Get-ChildItem "$libDir\*.jar" -ErrorAction SilentlyContinue
$jars += Get-ChildItem "$libFallback\*.jar" -ErrorAction SilentlyContinue
# 用正斜杠写进参数文件：javac 的 @file 解析会把反斜杠当转义符
$jarPaths = @()
foreach ($jar in $jars) {
    $jarPaths += ($jar.FullName -replace '\\', '/')
}
$classpath = [string]::Join([char]59, ($jarPaths | Select-Object -Unique))
$sources = Get-ChildItem (Join-Path $root 'src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }

# PowerShell 5.1 直接把带分号的 classpath 传给 javac 会出问题，改用 javac 的 @参数文件
$argFile = Join-Path $root 'build\javac-args.txt'
Set-Content -LiteralPath $argFile -Encoding ASCII -Value @(
    ('-cp "' + $classpath + '"')
)
& "$jdk\javac.exe" -encoding UTF-8 --release 21 "@$argFile" -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw '编译失败' }

Copy-Item (Join-Path $root 'plugin.yml') $classes -Force
Copy-Item (Join-Path $root 'config.yml') $classes -Force
if (Test-Path (Join-Path $root 'mobs.yml')) {
    Copy-Item (Join-Path $root 'mobs.yml') $classes -Force
}
if (Test-Path (Join-Path $root 'loot.yml')) {
    Copy-Item (Join-Path $root 'loot.yml') $classes -Force
}
if (Test-Path (Join-Path $root 'party.yml')) {
    Copy-Item (Join-Path $root 'party.yml') $classes -Force
}

$jar = Join-Path $dist ("nwndungeon-$version.jar")
& "$jdk\jar.exe" --create --file $jar -C $classes .
Write-Output ("已生成: " + $jar + "  (" + [math]::Round((Get-Item $jar).Length / 1KB, 1) + " KB)")
