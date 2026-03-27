# Build script for VirtualNetwork project
$projectName = "VirtualNetwork"
$srcDir = "src"
$binDir = "bin"
$jarFile = "$projectName.jar"

Write-Host "Building $projectName...`n"

# Create bin directory
if (-not (Test-Path $binDir)) {
    New-Item -ItemType Directory -Path $binDir | Out-Null
    Write-Host "[OK] Created $binDir directory"
}

# Compile Java files
Write-Host "Compiling Java files from $srcDir..."
javac -d $binDir -sourcepath $srcDir @(Get-ChildItem -Path $srcDir -Filter "*.java" -Recurse | ForEach-Object { $_.FullName })

if ($LASTEXITCODE -eq 0) {
    Write-Host "[OK] Compilation successful`n"
} else {
    Write-Host "[ERROR] Compilation failed"
    exit 1
}

# Create JAR file
Write-Host "Creating JAR: $jarFile"
$manifestContent = @"
Manifest-Version: 1.0
Main-Class: edu.wisc.cs.sdn.vnet.Main
Class-Path: .
"@

# Create temp manifest file
$manifestFile = "$binDir\MANIFEST.MF"
$manifestContent | Out-File -FilePath $manifestFile -Encoding ASCII

# Create JAR
jar cfm $jarFile $manifestFile -C $binDir .

if (Test-Path $jarFile) {
    Write-Host "[OK] JAR created: $jarFile`n"
    Write-Host "Build complete! Run with:"
    Write-Host "  java -jar $jarFile"
} else {
    Write-Host "[ERROR] JAR creation failed"
    exit 1
}
