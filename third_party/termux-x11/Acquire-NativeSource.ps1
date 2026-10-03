param([string] $Destination = "$PSScriptRoot/source")
$ErrorActionPreference = 'Stop'
$pinnedCommit = '0e1ebb4c180f4e8e7a14a80f7cd0db8301791b6d'
if (Test-Path -LiteralPath $Destination) {
    throw "Destination already exists; choose a new directory: $Destination"
}
git clone --no-checkout https://github.com/termux/termux-x11.git $Destination
if ($LASTEXITCODE -ne 0) { throw 'Source clone failed' }
git -C $Destination checkout --detach $pinnedCommit
if ($LASTEXITCODE -ne 0) { throw 'Pinned source checkout failed' }
git -C $Destination submodule update --init --recursive
if ($LASTEXITCODE -ne 0) { throw 'Native dependency source acquisition failed' }
Write-Output "Corresponding native source and dependencies ready: $Destination"
