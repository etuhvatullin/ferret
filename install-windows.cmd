@echo off
chcp 65001 >nul
rem Встановлення Ferret на Windows без прав адміністратора: копія в %LOCALAPPDATA%\Programs\Ferret, ярлики в «Пуск» і на
rem робочому столі, запуск. Далі Ferret оновлюється сам («Є нова версія — Оновити»).
rem Файл — з переносами рядків Windows (CRLF, .gitattributes): з переносами Linux cmd губив рядки, і DEST лишався
rem порожнім. Запобіжник нижче — щоб /MIR ніколи не пішов у порожній шлях (він видаляє зайве в теці призначення).
if not defined LOCALAPPDATA (
  echo Не знайдено теку LOCALAPPDATA — встановлення скасовано.
  pause
  exit /b 1
)
set "DEST=%LOCALAPPDATA%\Programs\Ferret"
if not exist "%~dp0Ferret\Ferret.exe" (
  echo Поруч із цим файлом немає теки Ferret — розпакуй архів повністю і запусти ще раз.
  pause
  exit /b 1
)
echo Встановлюю Ferret у %DEST% …
robocopy "%~dp0Ferret" "%DEST%" /MIR /NFL /NDL /NJH /NJS >nul
if errorlevel 8 (
  echo Не вдалося скопіювати файли. Закрий Ferret, якщо він відкритий, і запусти цей файл ще раз.
  pause
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "$w=New-Object -ComObject WScript.Shell; foreach($d in @([Environment]::GetFolderPath('Programs'),[Environment]::GetFolderPath('Desktop'))){ $s=$w.CreateShortcut((Join-Path $d 'Ferret.lnk')); $s.TargetPath='%DEST%\Ferret.exe'; $s.WorkingDirectory='%DEST%'; $s.Save() }"
echo Готово. Ярлик Ferret — у «Пуск» і на робочому столі.
start "" "%DEST%\Ferret.exe"
