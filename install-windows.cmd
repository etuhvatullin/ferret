@echo off
chcp 65001 >nul
rem Installs Ferret for the current user (no admin rights): copies it to %LOCALAPPDATA%\Programs\Ferret, adds
rem Start menu and desktop shortcuts and starts it. After that Ferret updates itself.
rem Keep this file with Windows line endings (CRLF, see .gitattributes): with LF cmd drops lines and DEST stays empty.
rem The guards below make sure robocopy /MIR never runs against an empty path (it deletes extra files in the target).
if not defined LOCALAPPDATA (
  echo LOCALAPPDATA is not set - installation cancelled.
  pause
  exit /b 1
)
set "DEST=%LOCALAPPDATA%\Programs\Ferret"
if not exist "%~dp0Ferret\Ferret.exe" (
  echo The Ferret folder is not next to this file - extract the whole archive and run it again.
  pause
  exit /b 1
)
echo Installing Ferret to %DEST% ...
robocopy "%~dp0Ferret" "%DEST%" /MIR /NFL /NDL /NJH /NJS >nul
if errorlevel 8 (
  echo Could not copy the files. Close Ferret if it is running and run this file again.
  pause
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "$w=New-Object -ComObject WScript.Shell; foreach($d in @([Environment]::GetFolderPath('Programs'),[Environment]::GetFolderPath('Desktop'))){ $s=$w.CreateShortcut((Join-Path $d 'Ferret.lnk')); $s.TargetPath='%DEST%\Ferret.exe'; $s.WorkingDirectory='%DEST%'; $s.Save() }"
echo Done. Ferret shortcuts are in the Start menu and on the desktop.
start "" "%DEST%\Ferret.exe"
