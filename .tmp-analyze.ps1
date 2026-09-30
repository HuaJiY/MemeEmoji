param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)][string[]]$Classes,
    [string]$Filter,
    [string]$Dest
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$mcJar = 'E:\Github\gradle_home\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged\1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2\minecraft-merged-1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2.jar'
$javap = 'C:\Program Files\Java\bin\javap.exe'

$builder = New-Object System.Text.StringBuilder
Write-Output "filter=[$Filter] out=[$Dest] classes=[$($Classes -join ',')]"
foreach ($cls in $Classes) {
    [void]$builder.AppendLine("===== $cls =====")
    $text = (& $javap -p -c -cp $mcJar $cls 2>&1 | Out-String -Width 400)
    [void]$builder.AppendLine($text)
}
$result = $builder.ToString()
if ($Filter) {
    $lines = $result -split "`r?`n"
    $hits = for ($i = 0; $i -lt $lines.Length; $i++) {
        if ($lines[$i] -match $Filter) { $i }
    }
    foreach ($h in $hits) {
        $from = [Math]::Max(0, $h - 40)
        $to = [Math]::Min($lines.Length - 1, $h + 40)
        Write-Output "----- hit at line $h -----"
        for ($j = $from; $j -le $to; $j++) { Write-Output $lines[$j] }
    }
} elseif ($Dest) {
    [System.IO.File]::WriteAllText($Dest, $result, [System.Text.UTF8Encoding]::new($false))
    Write-Output "wrote $Dest ($($result.Length) chars)"
} else {
    Write-Output $result
}
