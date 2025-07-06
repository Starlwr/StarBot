$input = 'target\dependencies.txt'
$output = 'target\dependency.json'

$pattern = '[\|\+\-\s\\]*([\w\.-]+):([\w\.-]+):jar:([\w\.-]+):(\w+)'

$deps = @()

Get-Content $input | ForEach-Object {
    if ($_ -match $pattern) {
        $groupId = $matches[1]
        $artifactId = $matches[2]
        $version = $matches[3]
        $scope = $matches[4]

        if ($scope -eq 'compile' -or $scope -eq 'runtime') {
            $deps += [PSCustomObject]@{
                groupId = $groupId
                artifactId = $artifactId
                version = $version
            }
        }
    }
}

$deps | ConvertTo-Json -Depth 3 | Out-File $output -Encoding utf8