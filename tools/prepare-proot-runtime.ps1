param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$jniDirectory = Join-Path $projectRoot 'app\src\main\jniLibs\arm64-v8a'
$prootTarget = Join-Path $jniDirectory 'libproot.so'
$loaderTarget = Join-Path $jniDirectory 'libproot-loader.so'
$packageVersion = '5.1.107.92'
$packageSha256 = '1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9'
$prootSha256 = 'ea47e17da8e6ff4882c169c6508861e5b4be9227e477c6020f4f14facc85c10d'
$loaderSha256 = '44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04'
$packageUrls = @(
    "https://termux.librehat.com/apt/termux-main/pool/main/p/proot/proot_${packageVersion}_aarch64.deb",
    "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_${packageVersion}_aarch64.deb"
)
# The ARM64 tracee loader lives at this exact path inside the Termux package.
$prootEntry = './data/data/com.termux/files/usr/bin/proot'
$loaderEntry = './data/data/com.termux/files/usr/libexec/proot/loader'

if (-not $Force -and (Test-Path -LiteralPath $prootTarget) -and (Test-Path -LiteralPath $loaderTarget)) {
    $currentProotSha256 = (Get-FileHash -LiteralPath $prootTarget -Algorithm SHA256).Hash.ToLowerInvariant()
    $currentLoaderSha256 = (Get-FileHash -LiteralPath $loaderTarget -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($currentProotSha256 -eq $prootSha256 -and $currentLoaderSha256 -eq $loaderSha256) {
        Write-Host "PRoot $packageVersion runtime is already prepared: $prootTarget and $loaderTarget"
        exit 0
    }
    Write-Host "Existing PRoot runtime does not match $packageVersion; regenerating both artifacts."
}

# Git Bash puts its GNU tar on PATH ahead of the Windows system tar; GNU tar
# misinterprets Windows paths (e.g. "C:\...") as remote host specs and fails
# with "Cannot connect to C: resolve failed". Use the Windows bsdtar directly.
$systemTar = Join-Path $env:SystemRoot 'System32\tar.exe'
if (-not (Test-Path -LiteralPath $systemTar)) {
    throw "Windows system tar not found at $systemTar"
}

$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ("wanxiang-proot-" + [guid]::NewGuid())
$debPath = Join-Path $temporaryRoot 'proot.deb'
$arDirectory = Join-Path $temporaryRoot 'ar'
$prootExtracted = Join-Path $temporaryRoot 'libproot.so'
$loaderExtracted = Join-Path $temporaryRoot 'libproot-loader.so'

try {
    New-Item -ItemType Directory -Path $arDirectory -Force | Out-Null
    $lastDownloadError = $null
    foreach ($url in $packageUrls) {
        try {
            Write-Host "Downloading PRoot runtime: $url"
            Invoke-WebRequest -Uri $url -OutFile $debPath
            $actualSha256 = (Get-FileHash -LiteralPath $debPath -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($actualSha256 -ne $packageSha256) {
                throw "Checksum mismatch: expected $packageSha256, got $actualSha256"
            }
            $lastDownloadError = $null
            break
        } catch {
            $lastDownloadError = $_
            Remove-Item -LiteralPath $debPath -Force -ErrorAction SilentlyContinue
        }
    }
    if ($null -ne $lastDownloadError) {
        throw $lastDownloadError
    }

    & $systemTar -xf $debPath -C $arDirectory
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to extract the Termux deb archive (tar exit=$LASTEXITCODE)"
    }
    $dataArchive = Get-ChildItem -LiteralPath $arDirectory -File |
        Where-Object { $_.Name -like 'data.tar.*' } |
        Select-Object -First 1
    if ($null -eq $dataArchive) {
        throw 'The Termux deb does not contain data.tar.*'
    }

    # The package data archive also contains docs and a 32-bit loader; extracting
    # the whole payload on Windows fails with EINVAL on deep paths
    # ('\\?\C:\...\copyright: Invalid argument'). Stream only the ARM64 tracer
    # and loader entries straight to their target files.
    $listing = & $systemTar -tf $dataArchive.FullName
    if ($LASTEXITCODE -ne 0 -or -not ($listing -contains $prootEntry) -or -not ($listing -contains $loaderEntry)) {
        throw "The Termux package does not contain both '$prootEntry' and '$loaderEntry'"
    }

    New-Item -ItemType Directory -Path $jniDirectory -Force | Out-Null
    # Start-Process -RedirectStandardOutput preserves raw bytes; PowerShell's
    # '>' operator would re-encode the binary as UTF-16 and corrupt the ELF.
    $process = Start-Process -FilePath $systemTar `
        -ArgumentList @('-xOf', $dataArchive.FullName, $prootEntry) `
        -RedirectStandardOutput $prootExtracted `
        -NoNewWindow -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        throw "Unable to extract the PRoot tracer (tar exit=$($process.ExitCode))"
    }

    $process = Start-Process -FilePath $systemTar `
        -ArgumentList @('-xOf', $dataArchive.FullName, $loaderEntry) `
        -RedirectStandardOutput $loaderExtracted `
        -NoNewWindow -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        throw "Unable to extract the PRoot loader (tar exit=$($process.ExitCode))"
    }

    $proot = Get-Item -LiteralPath $prootExtracted
    if ($proot.Length -le 4096 -or $proot.Length -gt 4MB) {
        throw 'The official package does not contain a usable ARM64 proot tracer'
    }

    $loader = Get-Item -LiteralPath $loaderExtracted
    if ($loader.Length -le 4096 -or $loader.Length -gt 4MB) {
        throw 'The official package does not contain a usable ARM64 proot loader'
    }

    foreach ($artifact in @($proot, $loader)) {
        $magic = [byte[]]::new(4)
        $stream = [IO.File]::OpenRead($artifact.FullName)
        try {
            [void]$stream.Read($magic, 0, $magic.Length)
        } finally {
            $stream.Dispose()
        }
        if ($magic[0] -ne 0x7f -or $magic[1] -ne 0x45 -or $magic[2] -ne 0x4c -or $magic[3] -ne 0x46) {
            throw "The extracted PRoot artifact is not an ELF file: $($artifact.Name)"
        }
    }

    $actualProotSha256 = (Get-FileHash -LiteralPath $prootExtracted -Algorithm SHA256).Hash.ToLowerInvariant()
    $actualLoaderSha256 = (Get-FileHash -LiteralPath $loaderExtracted -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualProotSha256 -ne $prootSha256) {
        throw "PRoot tracer checksum mismatch: expected $prootSha256, got $actualProotSha256"
    }
    if ($actualLoaderSha256 -ne $loaderSha256) {
        throw "PRoot loader checksum mismatch: expected $loaderSha256, got $actualLoaderSha256"
    }

    Move-Item -LiteralPath $prootExtracted -Destination $prootTarget -Force
    Move-Item -LiteralPath $loaderExtracted -Destination $loaderTarget -Force
    Write-Host "Prepared $prootTarget"
    Write-Host "PRoot SHA-256: $actualProotSha256"
    Write-Host "Prepared $loaderTarget"
    Write-Host "Loader SHA-256: $actualLoaderSha256"
} finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        $resolvedTemporaryRoot = (Resolve-Path -LiteralPath $temporaryRoot).Path
        $systemTemporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if ($resolvedTemporaryRoot.StartsWith($systemTemporaryRoot, [StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolvedTemporaryRoot -Recurse -Force
        }
    }
}
