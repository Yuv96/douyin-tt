@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul

set "DYTT_DIR=%~dp0"
set "DYTT_ENTRY=%DYTT_DIR%run_douyin_tt.py"
if not exist "%DYTT_ENTRY%" goto missing_entry
cd /d "%DYTT_DIR%" >nul 2>&1
if errorlevel 1 goto missing_entry

set "DYTT_PYTHONW="
set "DYTT_PYW="
set "DYTT_VERSION="
call :try_pythonw "%DYTT_DIR%.runtime\venv\Scripts\pythonw.exe"
if defined DYTT_PYTHONW goto launch_pythonw

for /f "delims=" %%P in ('where pyw.exe 2^>nul') do if not defined DYTT_PYW set "DYTT_PYW=%%P"
if defined DYTT_PYW (
  for %%P in ("%DYTT_PYW%") do set "DYTT_PY_CONSOLE=%%~dpPpy.exe"
  for %%V in (3.12 3.13 3.11 3.10 3) do call :try_launcher %%V
)
if defined DYTT_VERSION goto launch_pyw

for %%V in (312 313 311 310) do (
  call :try_pythonw "%LocalAppData%\Programs\Python\Python%%V\pythonw.exe"
  call :try_pythonw "%ProgramFiles%\Python%%V\pythonw.exe"
)
for /f "delims=" %%P in ('where pythonw.exe 2^>nul') do call :try_pythonw "%%P"
if defined DYTT_PYTHONW goto launch_pythonw
goto missing_python

:try_pythonw
if defined DYTT_PYTHONW exit /b 0
if not exist "%~1" exit /b 0
if not exist "%~dp1python.exe" exit /b 0
"%~dp1python.exe" -I -B -c "import sys, tkinter; sys.exit(sys.version_info < (3, 10))" >nul 2>&1
if not errorlevel 1 set "DYTT_PYTHONW=%~1"
exit /b 0

:try_launcher
if defined DYTT_VERSION exit /b 0
if not exist "%DYTT_PY_CONSOLE%" exit /b 0
"%DYTT_PY_CONSOLE%" -%~1 -I -B -c "import sys, tkinter; sys.exit(sys.version_info < (3, 10))" >nul 2>&1
if not errorlevel 1 set "DYTT_VERSION=-%~1"
exit /b 0

:launch_pythonw
start "" "%DYTT_PYTHONW%" -B "%DYTT_ENTRY%" >nul 2>&1
if errorlevel 1 goto launch_failed
exit /b 0

:launch_pyw
start "" "%DYTT_PYW%" %DYTT_VERSION% -B "%DYTT_ENTRY%" >nul 2>&1
if errorlevel 1 goto launch_failed
exit /b 0

:missing_entry
powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -Command "Add-Type -AssemblyName System.Windows.Forms; [void][System.Windows.Forms.MessageBox]::Show('请将启动器与完整项目放在同一目录。','抖音TT无法启动')" >nul 2>&1
exit /b 11

:missing_python
powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -Command "Add-Type -AssemblyName System.Windows.Forms; [void][System.Windows.Forms.MessageBox]::Show('请先安装 Python 3.10 或更新版本（含 Tk）。','抖音TT无法启动')" >nul 2>&1
exit /b 12

:launch_failed
powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -Command "Add-Type -AssemblyName System.Windows.Forms; [void][System.Windows.Forms.MessageBox]::Show('请检查 Python 安装后重试。','抖音TT无法启动')" >nul 2>&1
exit /b 1
