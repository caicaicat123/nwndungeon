# 编译 NWNDungeon：javac -> jar。产物在 dist\nwndungeon-<plugin.yml 里的版本>.jar
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdk = 'C:\Program Files\Java\jdk-21\bin'
# 编译依赖：优先用项目里的 lib\（放 paper-api.jar 进去即可），没有就退回本机的 simpfun-ops 目录
$lib = Join-Path $root 'lib'
if (-not (Test-Path (Join-Path $lib 'paper-api.jar'))) {
    $lib = 'C:\Users\ASUS\Documents\Projects\simpfun-ops\opsbridge\lib'
}
$classes = Join-Path $root 'build\classes'
$dist = Join-Path $root 'dist'

if (-not (Test-Path (Join-Path $lib 'paper-api.jar'))) {
    throw "缺少编译依赖：$lib\paper-api.jar"
}

$version = ((Get-Content (Join-Path $root 'plugin.yml') | Where-Object { $_ -match '^version:' }) -split ':', 2)[1].Trim()

Remove-Item -LiteralPath $classes -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $dist | Out-Null

$classpath = (Get-ChildItem "$lib\*.jar" | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem (Join-Path $root 'src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }

& "$jdk\javac.exe" -encoding UTF-8 --release 21 -cp $classpath -d $classes $sources
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
