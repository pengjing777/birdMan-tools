@echo off
setlocal EnableExtensions
chcp 65001 >nul
title 鸟友工具箱启动器

cd /d "%~dp0"
set "ROOT_DIR=%~dp0"
set "VENV_DIR=%ROOT_DIR%.venv"
set "PYTHON_EXE=%VENV_DIR%\Scripts\python.exe"

echo.
echo ========================================
echo        鸟友工具箱 Windows 启动器
echo ========================================
echo.

where py >nul 2>nul
if errorlevel 1 goto :install_python

py -3.13 -c "import sys; assert sys.version_info[:2] == (3, 13)" >nul 2>nul
if errorlevel 1 goto :install_python
goto :python_ready

:install_python
echo 未检测到 Python 3.13，正在使用 winget 安装...
where winget >nul 2>nul
if errorlevel 1 (
    echo.
    echo 未找到 winget。请先安装 Python 3.13：
    echo https://www.python.org/downloads/windows/
    echo 安装时请勾选 Add Python to PATH，然后重新运行本文件。
    pause
    exit /b 1
)

winget install --id Python.Python.3.13 -e --scope user --accept-package-agreements --accept-source-agreements
if errorlevel 1 (
    echo Python 3.13 安装失败。
    pause
    exit /b 1
)

if not exist "%LOCALAPPDATA%\Programs\Python\Python313\python.exe" (
    echo 已尝试安装 Python，但未找到 Python 3.13，请重新双击本文件。
    pause
    exit /b 1
)
goto :python_ready

:python_ready
if not exist "%PYTHON_EXE%" (
    echo 正在创建虚拟环境...
    py -3.13 -m venv "%VENV_DIR%"
    if errorlevel 1 (
        echo 创建虚拟环境失败。
        pause
        exit /b 1
    )
)

echo 正在检查并安装依赖，首次运行可能需要几分钟...
"%PYTHON_EXE%" -m pip install --disable-pip-version-check --upgrade pip
"%PYTHON_EXE%" -m pip install --disable-pip-version-check -r "%ROOT_DIR%requirements.txt"
if errorlevel 1 (
    echo 依赖安装失败，请检查网络连接后重新运行。
    pause
    exit /b 1
)

if not exist "%ROOT_DIR%config\settings.json" (
    copy /y "%ROOT_DIR%config\settings.example.json" "%ROOT_DIR%config\settings.json" >nul
)

echo 正在启动鸟友工具箱...
start "BirdsTools" /D "%ROOT_DIR%" "%PYTHON_EXE%" "%ROOT_DIR%app.py"

echo 等待本地服务启动...
set "READY="
for /l %%i in (1,1,30) do (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$c=New-Object Net.Sockets.TcpClient; try {$c.Connect('127.0.0.1',5009); exit 0} catch {exit 1} finally {$c.Dispose()}" >nul 2>nul
    if not errorlevel 1 (
        set "READY=1"
        goto :open_browser
    )
    timeout /t 1 /nobreak >nul
)

echo 服务启动超时，请检查新打开的窗口中的错误信息。
pause
exit /b 1

:open_browser
echo 正在打开浏览器...
start "" "http://127.0.0.1:5009"
echo.
echo 鸟友工具箱已启动，关闭本窗口不会停止后台服务。
timeout /t 3 /nobreak >nul
exit /b 0
