@echo off

cd /d %~dp0

if "%1"=="hide" goto CmdBegin
start mshta vbscript:createobject("wscript.shell").run("""%~0"" hide",0)(window.close)&&exit
:CmdBegin

copy ffplay.exe c:\windows\

netsh advfirewall set allprofiles state off

java -jar -Xms900m -Xmx1000m broadcast-server.jar


netsh advfirewall set allprofiles state on
S