$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ServerDir = Join-Path $Root "Server"
$RuntimeDir = Join-Path $Root ".runtime"
$JavaMajor = "11"
$JavaHome = Join-Path $RuntimeDir "jdk-$JavaMajor"
$JavaExe = Join-Path $JavaHome "bin\java.exe"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message"
}

function Fail([string]$Message) {
    throw $Message
}

Set-Location $Root

$IsGitClone = Test-Path (Join-Path $Root ".git")
$CacheProbe = Join-Path $ServerDir "data\cache\main_file_cache.dat2"

if ($IsGitClone) {
    Write-Step "Git clone detected - checking Git LFS"
    if (-not (Get-Command git.exe -ErrorAction SilentlyContinue)) {
        Fail "This is a Git clone, but Git for Windows was not found."
    }

    & git lfs version
    if ($LASTEXITCODE -ne 0) {
        Fail "Git LFS is not available. Reinstall/update Git for Windows with Git LFS enabled."
    }

    & git lfs install
    if ($LASTEXITCODE -ne 0) {
        Fail "git lfs install failed."
    }

    Write-Step "Downloading/checking 2009Scape LFS files"
    & git lfs pull
    if ($LASTEXITCODE -ne 0) {
        Fail "git lfs pull failed."
    }
    & git lfs checkout
    if ($LASTEXITCODE -ne 0) {
        Fail "git lfs checkout failed."
    }
}
else {
    Write-Step "Release ZIP detected - Git and Git LFS are not required"
}

if (-not (Test-Path $CacheProbe)) {
    Fail "The RuneScape cache is missing. Use the official Killer Edition release ZIP or run git lfs pull in a Git clone."
}
$FirstLine = Get-Content -LiteralPath $CacheProbe -TotalCount 1 -ErrorAction SilentlyContinue
if ($FirstLine -eq "version https://git-lfs.github.com/spec/v1") {
    Fail "The RuneScape cache is still an LFS pointer instead of the real cache file."
}

if (-not (Test-Path $JavaExe)) {
    Write-Step "Downloading a private Temurin JDK $JavaMajor runtime"

    $Arch = $env:PROCESSOR_ARCHITECTURE
    if ($Arch -eq "AMD64" -or $Arch -eq "x86") {
        $AdoptiumArch = "x64"
    }
    elseif ($Arch -eq "ARM64") {
        $AdoptiumArch = "aarch64"
    }
    else {
        Fail "Unsupported Windows architecture: $Arch"
    }

    New-Item -ItemType Directory -Force -Path $RuntimeDir | Out-Null
    $Zip = Join-Path $RuntimeDir "jdk-$JavaMajor.zip"
    $Extract = Join-Path $RuntimeDir "jdk-$JavaMajor-extract"

    Remove-Item $Zip -Force -ErrorAction SilentlyContinue
    Remove-Item $Extract -Recurse -Force -ErrorAction SilentlyContinue

    $Url = "https://api.adoptium.net/v3/binary/latest/$JavaMajor/ga/windows/$AdoptiumArch/jdk/hotspot/normal/eclipse"
    Write-Host "Downloading from Adoptium..."
    Invoke-WebRequest -Uri $Url -OutFile $Zip -UseBasicParsing

    Expand-Archive -LiteralPath $Zip -DestinationPath $Extract -Force
    $JdkFolder = Get-ChildItem -LiteralPath $Extract -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName "bin\java.exe") } |
        Select-Object -First 1

    if ($null -eq $JdkFolder) {
        Fail "Downloaded JDK archive did not contain a usable Java runtime."
    }

    Remove-Item $JavaHome -Recurse -Force -ErrorAction SilentlyContinue
    Move-Item -LiteralPath $JdkFolder.FullName -Destination $JavaHome
    Remove-Item $Extract -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $Zip -Force -ErrorAction SilentlyContinue
}
else {
    Write-Step "Using existing private JDK $JavaMajor runtime"
}

$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"

& $JavaExe -version
if ($LASTEXITCODE -ne 0) {
    Fail "The private Java runtime could not be started."
}

$ServerJar = Join-Path $ServerDir "server.jar"
if ($IsGitClone -or -not (Test-Path $ServerJar)) {
    Write-Step "Building 2009Scape"
    Push-Location $ServerDir
    try {
        & ".\mvnw.cmd" clean package -DskipTests
        if ($LASTEXITCODE -ne 0) {
            Fail "Maven build failed."
        }

        $BuiltJar = Get-ChildItem -LiteralPath (Join-Path $ServerDir "target") -Filter "*-jar-with-dependencies.jar" |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1

        if ($null -eq $BuiltJar) {
            Fail "Build completed but the server JAR was not found."
        }

        Copy-Item -LiteralPath $BuiltJar.FullName -Destination $ServerJar -Force
    }
    finally {
        Pop-Location
    }
}
else {
    Write-Step "Using prebuilt server.jar from release ZIP"
}

Write-Step "Setup complete"
Write-Host "Private Java: $JavaHome"
Write-Host "Server JAR:    $ServerJar"
Write-Host ""
Write-Host "Start the server with: server.bat"
