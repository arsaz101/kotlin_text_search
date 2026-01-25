# Build script that sets GRADLE_USER_HOME to avoid permission issues
$env:GRADLE_USER_HOME = "$PSScriptRoot\.gradle"
.\gradlew.bat $args
