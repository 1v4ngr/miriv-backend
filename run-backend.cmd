@echo off
cd /d "%~dp0"
set "JAVA_HOME=C:\Users\igomr\.jdks\ms-25.0.4"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "SERVER_PORT=18080"
"C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.0.1\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" spring-boot:run
