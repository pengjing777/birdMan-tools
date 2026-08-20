import json
import os
import shutil
import sys

_CONFIG_DIR = os.path.dirname(os.path.abspath(__file__))
_BUNDLED_SETTINGS_PATH = os.path.join(_CONFIG_DIR, "settings.json")
_APP_NAME = "鸟友工具箱"


def _legacy_settings_paths():
    base_dir = os.path.expanduser("~/Library/Application Support")
    return [
        os.path.join(base_dir, "ToolBox", "settings.json"),
        os.path.join(base_dir, "AI观鸟助手", "settings.json"),
    ]


def _get_settings_path():
    """Use a user-writable config path when running as a bundled macOS app."""
    if getattr(sys, "frozen", False):
        app_support_dir = os.path.expanduser(
            os.path.join("~/Library/Application Support", _APP_NAME)
        )
        os.makedirs(app_support_dir, exist_ok=True)
        settings_path = os.path.join(app_support_dir, "settings.json")
        legacy_settings_path = next((path for path in _legacy_settings_paths() if os.path.exists(path)), None)
        if not os.path.exists(settings_path) and legacy_settings_path:
            shutil.copy2(legacy_settings_path, settings_path)
        if not os.path.exists(settings_path) and os.path.exists(_BUNDLED_SETTINGS_PATH):
            shutil.copy2(_BUNDLED_SETTINGS_PATH, settings_path)
        return settings_path
    return _BUNDLED_SETTINGS_PATH


_SETTINGS_PATH = _get_settings_path()

# 默认配置（当 settings.json 不存在或缺少字段时的兜底值）
_DEFAULTS = {
    "port": 5009,
    "docs_base_path": "/Users/wangpengjing/Documents/wangpjProject/tax_pay_ext/docs",
    "skills_base_path": "/Users/wangpengjing/Documents/wangpjProject/tax_pay_ext/.agents/skills",
    "ssh": {
        "hostname": "192.168.88.87",
        "port": 22,
        "username": "root",
        "password": "1qaz2wsx3edc4rfv",
    },
    "db": {
        "enabled": True,
        "host": "localhost",
        "port": 3306,
        "user": "root",
        "password": "awsd620228",
        "database": "sby",
        "charset": "utf8mb4",
        "connect_timeout": 3,
    },
    "log_mapping": {
        "tax-pay-ext-bossMgr": ["boss", "tomcat-web-8180"],
        "tax-pay-ext-quartMgr": ["quart", "tomcat-web-8180"],
        "trans-rpcprovider": ["provider", "tomcat-trans-rpcprovider-8480"],
        "trans-business": ["trans", "tomcat-trans-8280"],
    },
    "fund_transfer_test": {
        "oracle": {
            "user": "paymentdb",
            "password": "",
            "dsn": "192.168.88.87:1521/orcl",
            "instant_client_dir": "",
        },
        "charge_url": "http://192.168.88.87:8180/BossMgr/ceBankQuery/charge.html",
        "login_url": "http://192.168.88.87:8180/BossMgr/login.jsp",
    },
    "image_generation": {
        "model": "cogview-4",
        "api_key": "",
        "base_url": "https://open.bigmodel.cn/api/paas/v4",
    },
    "deepseek": {
        "model": "deepseek-v4-flash",
        "api_key": "",
        "base_url": "https://api.deepseek.com",
        "timeout": 90,
        "max_tokens": 0,
    },
    "ai_bird_preferences": {
        "province": "",
        "city": "",
        "district": "",
        "interested_birds": "鸮,一级保护动物",
        "uninterested_birds": "麻雀,白头鹎,绿头鸭,鸳鸯,珠颈斑鸠,喜鹊,灰喜鹊,灰椋鸟,大嘴乌鸦,小鷿鷈,凤头鷿鷈,普通鸬鹚,鸿雁,灰头绿啄木鸟,大斑啄木鸟,普通翠鸟,家燕,戴胜,白鹭,苍鹭",
    },
    "bird_record_blacklist": [],
    "service_vue_frontend": {
        "project_path": "/Users/wangpengjing/sbyProject/tax_pay_ext/bossmgr-new-console",
        "command": "pnpm dev",
        "page_url": "http://localhost:8080/BossMgr/new/",
        "login_page_url": "http://localhost:8080/BossMgr/new/login.html",
        "login_action_url": "http://localhost:8080/BossMgr/j_spring_security_check",
        "username": "admin@fws.com",
        "password": "1234Qwer!",
    },
    "network_config": {
        "manual": {
            "ip": "192.168.40.232",
            "subnet": "255.255.255.0",
            "router": "192.168.40.1",
        },
    },
    "desktop_assistant": {
        "enabled": True,
        "icon_path": "assets/assistant_kingfisher_transparent.png",
        "emoji": "🤪",
        "menu_items": [
            {
                "label": "配置管理",
                "action": "open_tool",
                "target": "config-manager",
                "enabled": True,
            },
            {
                "label": "鸟种记录",
                "action": "open_tool",
                "target": "bird-records",
                "enabled": True,
            },
        ],
    },
    "git_branch_manager": {
        "project_path": "/Users/wangpengjing/sbyProject/tax_pay_ext",
        "branches": ["test", "release"],
        "auto_merge_release_after_switch": False,
        "current_project": "tax_pay_ext",
        "projects": [
            {
                "id": "tax_pay_ext",
                "name": "tax_pay_ext",
                "project_path": "/Users/wangpengjing/sbyProject/tax_pay_ext",
                "branches": ["test", "release"],
            },
            {
                "id": "risk-cloud",
                "name": "risk-cloud",
                "project_path": "/Users/wangpengjing/sbyProject/risk-cloud",
                "branches": ["main", "test"],
            },
        ],
    },
    "home_tool_order": ["config-manager", "bird-records", "ai-bird-chat", "photo-classify", "bird-navigation"],
    "home_tool_enabled": {"photo-classify": False},
}


def _deep_merge(base, override):
    """递归合并 dict，override 中的值覆盖 base"""
    result = base.copy()
    for k, v in override.items():
        if k in result and isinstance(result[k], dict) and isinstance(v, dict):
            result[k] = _deep_merge(result[k], v)
        else:
            result[k] = v
    return result


class ConfigManager:
    """配置管理器，读写 settings.json"""

    def __init__(self):
        self._data = dict(_DEFAULTS)
        self._load()

    def _load(self):
        if os.path.isfile(_SETTINGS_PATH):
            try:
                with open(_SETTINGS_PATH, "r", encoding="utf-8") as f:
                    user_data = json.load(f)
                self._data = _deep_merge(self._data, user_data)
            except Exception as e:
                print(f"[Config] 加载 settings.json 失败: {e}")
        # Earlier desktop builds used different Application Support directories.
        # Keep a configured Key when the new app directory already exists but has no Key yet.
        current_key = str((self._data.get("deepseek") or {}).get("api_key") or "").strip()
        if not current_key:
            for legacy_path in _legacy_settings_paths():
                if legacy_path == _SETTINGS_PATH or not os.path.isfile(legacy_path):
                    continue
                try:
                    with open(legacy_path, "r", encoding="utf-8") as f:
                        legacy_key = str(((json.load(f).get("deepseek") or {}).get("api_key") or "")).strip()
                    if legacy_key:
                        self._data.setdefault("deepseek", {})["api_key"] = legacy_key
                        self._save()
                        break
                except Exception:
                    continue

    def _save(self):
        os.makedirs(_CONFIG_DIR, exist_ok=True)
        with open(_SETTINGS_PATH, "w", encoding="utf-8") as f:
            json.dump(self._data, f, ensure_ascii=False, indent=2)

    # ---- 读取 ----

    @property
    def port(self):
        return self._data.get("port", _DEFAULTS["port"])

    @property
    def docs_base_path(self):
        return self._data.get("docs_base_path", _DEFAULTS["docs_base_path"])

    @property
    def skills_base_path(self):
        return self._data.get("skills_base_path", _DEFAULTS["skills_base_path"])

    @property
    def ssh_config(self):
        return dict(self._data.get("ssh", _DEFAULTS["ssh"]))

    @property
    def db_config(self):
        cfg = dict(self._data.get("db", _DEFAULTS["db"]))
        cfg.pop("enabled", None)
        cfg.setdefault("connect_timeout", 3)
        return cfg

    @property
    def db_enabled(self):
        """是否启用数据库；关闭后由应用使用内存缓存。"""
        return bool(self._data.get("db", {}).get("enabled", _DEFAULTS["db"]["enabled"]))

    @property
    def log_mapping(self):
        return dict(self._data.get("log_mapping", _DEFAULTS["log_mapping"]))

    def get_all(self):
        """返回完整配置（不含 _ 开头的内部字段）"""
        return {k: v for k, v in self._data.items() if not k.startswith("_")}

    # ---- 写入 ----

    def update(self, updates: dict):
        """更新配置并保存到文件"""
        self._data = _deep_merge(self._data, updates)
        self._save()


# 全局单例
_manager = ConfigManager()


# 兼容旧 config.py 的导出名
DOCS_BASE_PATH = _manager.docs_base_path
SKILLS_BASE_PATH = _manager.skills_base_path
SSH_CONFIG = _manager.ssh_config
DB_CONFIG = _manager.db_config
LOG_MAPPING = _manager.log_mapping
PORT = _manager.port

# 导出给 app.py 使用
__all__ = [
    "_manager",
    "DOCS_BASE_PATH",
    "SKILLS_BASE_PATH",
    "SSH_CONFIG",
    "DB_CONFIG",
    "LOG_MAPPING",
    "PORT",
]
