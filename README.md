# 鸟友工具箱

一个集成观鸟记录查询、保护动物名录、照片分类、配置管理等功能的本地工具箱。

## Windows 使用

将整个项目文件夹复制到 Windows 电脑，双击：

```text
启动鸟友工具箱.bat
```

启动器会自动完成以下操作：

1. 检查并安装 Python 3.13
2. 创建项目虚拟环境
3. 安装 `requirements.txt` 中的依赖
4. 启动应用
5. 自动打开浏览器页面

访问地址：

```text
http://127.0.0.1:5009
```

首次启动需要联网安装 Python 和依赖，可能需要几分钟。Windows 10/11 建议使用系统自带的 `winget`。

## macOS 使用

双击打开：

```text
release/鸟友工具箱-macOS.dmg
```

将“鸟友工具箱”拖入“应用程序”即可。

首次打开如果 macOS 提示无法验证开发者，可以右键应用并选择“打开”。

当前提供的 macOS 安装包适用于 Apple Silicon（arm64）Mac。

## 开发模式启动

如果电脑已经安装 Python，可以直接运行：

```bash
pip install -r requirements.txt
python app.py
```

然后打开：

```text
http://127.0.0.1:5009
```

桌面窗口模式可以运行：

```bash
python desktop_app.py
```

## 数据库和缓存

在“配置管理”中可以设置数据库：

- 启用数据库：收藏、鸟种记录等数据可以持久化保存。
- 关闭数据库：保护动物名录和查询结果使用内存缓存。
- 缓存有效期：5 分钟。
- 应用每次启动时会自动预热保护动物名录缓存。

未配置数据库时，保护动物名录会优先从内置 Excel 文件读取，不影响名录查询和保护级别匹配。

## 观鸟数据查询说明

查询使用公开的观鸟数据接口。请合理控制查询频率，不要绕过验证码、访问限制或权限控制，也不要批量传播个人信息和敏感鸟点位置。

如果接口要求验证码，请按页面提示完成验证。重复查询会优先使用五分钟缓存，减少对数据接口的请求。

## 配置文件

首次使用时可以参考：

```text
config/settings.example.json
```

实际配置文件为：

```text
config/settings.json
```

请不要把真实的数据库密码、SSH 密码、API Key 或其他敏感信息发送给朋友，也不要提交到 GitHub。

## Windows 打包

在 Windows 环境中可以使用：

```powershell
.\build-installer.ps1
```

该脚本需要 Python、PyInstaller 和 Inno Setup，最终生成：

```text
release/BirdsTools-Setup.exe
```

## macOS 打包

在 macOS 环境中运行：

```bash
./build-macos.sh
```

最终生成：

```text
release/鸟友工具箱-macOS.dmg
```

## Android 打包

项目已提供独立 Android 工程，位于 `android/`。当前版本内置保护动物名录，鸟类记录保存在手机本地，支持从手机选择照片，并可直接联网查询公开观鸟记录；不依赖电脑上的 Flask 服务。

### 生成 APK

使用 Android Studio 打开项目中的 `android/` 目录，等待 Gradle 同步完成后执行 `Build > Build APK(s)`。生成的调试 APK 通常位于：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

首次构建需要 Android Studio（包含 Android SDK 和 JDK 17）。

打包目录 `build/`、`dist/`、`release/` 和 `.venv/` 都是生成文件或本地环境，不需要提交到 GitHub。
