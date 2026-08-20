# -*- mode: python ; coding: utf-8 -*-

from PyInstaller.utils.hooks import collect_submodules

hiddenimports = (
    collect_submodules("webview")
    + collect_submodules("oracledb")
    + collect_submodules("objc")
)

a = Analysis(
    ["desktop_app.py"],
    pathex=["."],
    binaries=[],
    datas=[
        ("templates", "templates"),
        ("config/settings.example.json", "config"),
        ("assets", "assets"),
        ("国家重点保护野生动物名录.xlsx", "."),
    ],
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="BirdsTools",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name="BirdsTools",
)

app = BUNDLE(
    coll,
    name="鸟友工具箱.app",
    icon="assets/toolbox_kingfisher_icon_1024.png",
    bundle_identifier="local.birds-tools",
    info_plist={
        "CFBundleName": "鸟友工具箱",
        "CFBundleDisplayName": "鸟友工具箱",
        "NSHighResolutionCapable": True,
    },
)
