# -*- mode: python ; coding: utf-8 -*-

from PyInstaller.utils.hooks import collect_submodules


hiddenimports = collect_submodules("webview") + collect_submodules("oracledb")


a = Analysis(
    ["desktop_app.py"],
    pathex=[],
    binaries=[],
    datas=[
        ("templates", "templates"),
        ("config/settings.json", "config"),
        ("assets", "assets"),
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
    upx=True,
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
    upx=True,
    upx_exclude=[],
    name="BirdsTools",
)
app = BUNDLE(
    coll,
    name="鸟友工具箱.app",
    icon="assets/toolbox_kingfisher_icon_1024.png",
    bundle_identifier="local.birds-tools",
    info_plist={
        "CFBundleName": "BirdsTools",
        "CFBundleDisplayName": "鸟友工具箱",
        "NSHighResolutionCapable": "True",
    },
)
