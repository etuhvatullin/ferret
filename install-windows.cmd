@echo off
chcp 65001 >nul
rem Installs Ferret for the current user (no admin rights): asks where (Enter - %LOCALAPPDATA%\Programs\Ferret,
rem 2 - a folder picker), copies it there, adds Start menu and desktop shortcuts and starts it. After that Ferret
rem updates itself in the same folder.
rem Keep this file with Windows line endings (CRLF, see .gitattributes): with LF cmd drops lines and DEST stays empty.
rem robocopy /MIR deletes extra files in the target, so it never runs against an empty path or someone else's folder:
rem the picker (PowerShell after "exit /b 0" below) returns only a folder named Ferret that is new, empty or Ferret's.
rem The answer is a digit, not a letter: with a Cyrillic keyboard layout the C key types a Cyrillic letter.
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
echo Ferret will be installed to:
echo   %DEST%
echo.
echo   Enter      - install there
echo   2, Enter   - choose another folder
set "ANSWER="
set /p "ANSWER=> "
if not defined ANSWER goto install
set "FERRET_SELF=%~f0"
set "FERRET_PICK=%TEMP%\ferret-folder-%RANDOM%.txt"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$t = [IO.File]::ReadAllText($env:FERRET_SELF); iex $t.Substring($t.IndexOf('#ferret-' + 'picker'))"
if errorlevel 1 (
  echo Could not open the folder picker. Copy the Ferret folder from this archive where you want it and run Ferret.exe from there.
  pause
  exit /b 1
)
set "DEST="
if exist "%FERRET_PICK%" set /p DEST=<"%FERRET_PICK%"
del "%FERRET_PICK%" 2>nul
if not defined DEST (
  echo Installation cancelled.
  pause
  exit /b 1
)
:install
echo Installing Ferret to %DEST% ...
robocopy "%~dp0Ferret" "%DEST%" /MIR /NFL /NDL /NJH /NJS >nul
if errorlevel 8 (
  echo Could not copy the files. Close Ferret if it is running and run this file again.
  pause
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "$w=New-Object -ComObject WScript.Shell; foreach($d in @([Environment]::GetFolderPath('Programs'),[Environment]::GetFolderPath('Desktop'))){ $s=$w.CreateShortcut((Join-Path $d 'Ferret.lnk')); $s.TargetPath=(Join-Path $env:DEST 'Ferret.exe'); $s.WorkingDirectory=$env:DEST; $s.Save() }"
echo Done. Ferret shortcuts are in the Start menu and on the desktop.
start "" "%DEST%\Ferret.exe"
exit /b 0

#ferret-picker
# The folder picker for answer 2: writes the install folder to $env:FERRET_PICK, nothing on Cancel, exit 2 on failure.
# Ferret goes to a Ferret folder inside the chosen one (or into the chosen folder if it is named Ferret).
$ErrorActionPreference = 'Stop'
try {
  Add-Type -AssemblyName System.Windows.Forms
  $owner = New-Object System.Windows.Forms.Form -Property @{ TopMost = $true }   # without it the picker opens behind this window
  $pick = New-Object System.Windows.Forms.FolderBrowserDialog -Property @{
    Description = 'Choose a folder for Ferret. It will be installed into a "Ferret" folder inside it.'
    RootFolder = 'MyComputer'
  }
  $say = { param($text) [void][System.Windows.Forms.MessageBox]::Show($owner, $text, 'Ferret', 'OK', 'Warning') }
  while ($pick.ShowDialog($owner) -eq 'OK') {
    $dir = $pick.SelectedPath
    if (-not $dir) { continue }   # "This PC", network and other places that are not folders
    $dest = if ((Split-Path -Leaf $dir) -eq 'Ferret') { $dir } else { Join-Path $dir 'Ferret' }
    # robocopy /MIR would delete everything else in it: only a new, empty or Ferret's own folder
    $foreign = $true
    try { $foreign = (Test-Path -LiteralPath $dest) -and -not (Test-Path -LiteralPath (Join-Path $dest 'Ferret.exe')) -and [bool](Get-ChildItem -LiteralPath $dest -Force | Select-Object -First 1) } catch { }
    if ($foreign) {
      & $say "$dest already holds other files. Choose another folder: Ferret replaces everything in its own folder."
      continue
    }
    # The updater needs write access to the folder that holds Ferret (Updater.install) - check it now, so Program Files
    # and other admin-only places are refused here and not at the first update.
    $parent = Split-Path -Parent $dest
    $probe = Join-Path $parent ('.ferret-' + [guid]::NewGuid().ToString('N'))
    try { [IO.File]::WriteAllText($probe, ''); Remove-Item -LiteralPath $probe } catch {
      & $say "Ferret can't write to $parent without admin rights, so it couldn't update itself there (Program Files is one such place). Choose another folder."
      continue
    }
    [IO.File]::WriteAllText($env:FERRET_PICK, $dest, (New-Object System.Text.UTF8Encoding $false))
    break
  }
} catch { exit 2 }
