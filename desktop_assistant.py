import json
import os
import shlex
import subprocess
import sys
import threading
import traceback
import urllib.error
import urllib.parse
import urllib.request
import webbrowser

import objc
from PyObjCTools import AppHelper
from AppKit import (
    NSBackingStoreBuffered,
    NSBezierPath,
    NSColor,
    NSCompositingOperationSourceOver,
    NSFont,
    NSFontAttributeName,
    NSForegroundColorAttributeName,
    NSImage,
    NSMakePoint,
    NSMakeRect,
    NSMenu,
    NSMenuItem,
    NSEvent,
    NSFloatingWindowLevel,
    NSStatusWindowLevel,
    NSView,
    NSWindow,
    NSWindowCollectionBehaviorCanJoinAllSpaces,
    NSWindowCollectionBehaviorFullScreenAuxiliary,
    NSWindowStyleMaskBorderless,
)
from Foundation import NSObject, NSMakeSize, NSString

from config import _manager

ASSISTANT_LOG_PATH = "/private/tmp/toolbox_assistant.log"
ASSISTANT_SIZE = 104


ASSISTANT_DEFAULTS = {
    "enabled": True,
    "icon_path": "assets/assistant_kingfisher_transparent.png",
    "emoji": "🤪",
    "menu_items": [
        {"label": "配置管理", "action": "open_tool", "target": "config-manager", "enabled": True},
        {"label": "鸟种记录", "action": "open_tool", "target": "bird-records", "enabled": True},
        {"label": "照片分类管理", "action": "open_tool", "target": "photo-classify", "enabled": True},
    ],
}


class AssistantMenuTarget(NSObject):
    controller = objc.ivar()

    def initWithController_(self, controller):
        self = objc.super(AssistantMenuTarget, self).init()
        if self is None:
            return None
        self.controller = controller
        return self

    def assistantMenuAction_(self, sender):
        item = sender.representedObject()
        self.controller.run_action(dict(item or {}))

    def assistantSwitchBranch_(self, sender):
        payload = sender.representedObject() or {}
        branch = str(payload.get("branch") or "")
        project_id = str(payload.get("project_id") or "")
        if branch:
            self.controller.switch_git_branch(branch, project_id or None)


class AssistantEmojiView(NSView):
    controller = objc.ivar()
    drag_start = objc.ivar()
    window_start = objc.ivar()

    def initWithFrame_controller_(self, frame, controller):
        self = objc.super(AssistantEmojiView, self).initWithFrame_(frame)
        if self is None:
            return None
        self.controller = controller
        self.drag_start = None
        self.window_start = None
        return self

    def isFlipped(self):
        return True

    def acceptsFirstMouse_(self, event):
        return True

    def drawRect_(self, dirty_rect):
        bounds = self.bounds()
        if _draw_assistant_icon(self, bounds):
            return
        circle = NSBezierPath.bezierPathWithOvalInRect_(bounds)
        NSColor.colorWithCalibratedWhite_alpha_(1.0, 0.92).setFill()
        circle.fill()
        NSColor.colorWithCalibratedRed_green_blue_alpha_(0.08, 0.12, 0.20, 0.16).setStroke()
        circle.setLineWidth_(1.0)
        circle.stroke()

        emoji = NSString.stringWithString_(self.controller.current_emoji or ASSISTANT_DEFAULTS["emoji"])
        font = NSFont.systemFontOfSize_(42)
        attrs = {
            NSFontAttributeName: font,
            NSForegroundColorAttributeName: NSColor.blackColor(),
        }
        text_size = emoji.sizeWithAttributes_(attrs)
        rect = NSMakeRect(
            (bounds.size.width - text_size.width) / 2,
            (bounds.size.height - text_size.height) / 2 - 1,
            text_size.width,
            text_size.height,
        )
        emoji.drawInRect_withAttributes_(rect, attrs)

    def mouseDown_(self, event):
        if event.modifierFlags() & (1 << 18):
            self.controller.show_menu(event, self)
            return
        if event.clickCount() >= 2:
            self.controller.run_action({"label": "打开工具箱", "action": "open_toolbox", "target": ""})
            return
        self.drag_start = NSEvent.mouseLocation()
        frame = self.window().frame()
        self.window_start = NSMakePoint(frame.origin.x, frame.origin.y)

    def mouseDragged_(self, event):
        if self.drag_start is None or self.window_start is None:
            return
        current = NSEvent.mouseLocation()
        dx = current.x - self.drag_start.x
        dy = current.y - self.drag_start.y
        frame = self.window().frame()
        frame.origin.x = self.window_start.x + dx
        frame.origin.y = self.window_start.y + dy
        self.window().setFrame_display_(frame, True)

    def rightMouseDown_(self, event):
        self.controller.show_menu(event, self)

    def menuForEvent_(self, event):
        return self.controller.build_menu()


class DesktopAssistantController:
    def __init__(self, main_window, base_url):
        self.main_window = main_window
        self.base_url = base_url.rstrip("/")
        self.window = None
        self.view = None
        self.menu_target = None
        self.current_icon = None
        self.current_icon_path = ""
        self.current_emoji = ASSISTANT_DEFAULTS["emoji"]
        self._signature = None
        self._lock = threading.RLock()
        self._stopped = threading.Event()
        self._poller = None

    def start(self):
        self._log("assistant.start")
        self._call_on_main(self._sync_from_config)
        self._poller = threading.Thread(target=self._poll_config, daemon=True)
        self._poller.start()

    def prepare_initial_window(self):
        pass

    def stop(self):
        self._stopped.set()
        self._call_on_main(self._destroy_window)

    def run_action(self, item):
        action = str(item.get("action") or "").strip()
        target = str(item.get("target") or "").strip()
        label = str(item.get("label") or "菜单项").strip()

        try:
            if action == "open_toolbox":
                self._open_path("/")
                return {"status": "ok"}
            if action == "open_tool":
                if target:
                    self._open_path(f"/tools/{target}")
                return {"status": "ok"}
            if action == "open_url":
                if target:
                    webbrowser.open(target)
                return {"status": "ok"}
            if action == "copy_text":
                self._copy_text(target)
                return {"status": "ok"}
            if action == "run_command":
                if target:
                    subprocess.Popen(shlex.split(target))
                return {"status": "ok"}
            if action == "start_service_vue":
                result = self._request_json("/api/service-vue/start", method="POST")
                self._open_path("/tools/service-vue")
                return result
            if action == "open_service_vue_url":
                webbrowser.open(f"{self.base_url}/api/service-vue/auto-login")
                return {"status": "ok"}
            if action == "git_branches":
                self._open_path("/tools/git-branch-manager")
                return {"status": "ok"}
            if action == "toggle_network":
                result = self.toggle_network_config()
                return result
            if action == "disable_assistant":
                _manager.update({"desktop_assistant": {"enabled": False}})
                self._destroy_window()
                return {"status": "ok"}
            return {"status": "error", "error": f"未知动作: {action or label}"}
        except Exception as exc:
            return {"status": "error", "error": str(exc)}

    def get_git_status(self, project_id=None):
        query = ""
        if project_id:
            query = "?project_id=" + urllib.parse.quote(str(project_id))
        return self._request_json(f"/api/git-branch-manager/status{query}")

    def switch_git_branch(self, branch, project_id=None):
        payload = {"branch": str(branch or "").strip()}
        if project_id:
            payload["project_id"] = str(project_id)
        return self._request_json("/api/git-branch-manager/switch", method="POST", payload=payload)

    def get_network_status(self):
        local_status = self._get_wifi_network_status()
        if not local_status.get("error"):
            return local_status
        return self._request_json("/api/network")

    def _get_wifi_network_status(self):
        service = "Wi-Fi"
        try:
            completed = subprocess.run(
                ["networksetup", "-getinfo", service],
                check=True,
                capture_output=True,
                text=True,
                timeout=10,
            )
        except Exception as exc:
            return {"error": str(exc)}

        result = {"dhcp": False, "ip": "", "subnet": "", "router": "", "service": service}
        for raw_line in (completed.stdout or "").splitlines():
            line = raw_line.strip()
            if line.startswith("DHCP Configuration"):
                result["dhcp"] = True
            elif line.startswith("Manual Configuration"):
                result["dhcp"] = False
            elif line.startswith("IP address:"):
                result["ip"] = line.split(":", 1)[1].strip()
            elif line.startswith("Subnet mask:"):
                result["subnet"] = line.split(":", 1)[1].strip()
            elif line.startswith("Router:"):
                router = line.split(":", 1)[1].strip()
                if router and router != "none":
                    result["router"] = router
        return result

    def toggle_network_config(self):
        current = self.get_network_status()
        if current.get("error"):
            self._log("toggle network status error: " + str(current.get("error")))
            return {"status": "error", "error": current.get("error")}

        before_mode = "DHCP" if current.get("dhcp") else "手动"
        if current.get("dhcp"):
            manual = ((_manager.get_all().get("network_config") or {}).get("manual") or {})
            payload = {
                "mode": "manual",
                "ip": manual.get("ip") or current.get("ip") or "192.168.40.232",
                "subnet": manual.get("subnet") or current.get("subnet") or "255.255.255.0",
                "router": manual.get("router") or current.get("router") or "192.168.40.1",
            }
        else:
            payload = {"mode": "dhcp"}

        result = self._request_json("/api/network", method="POST", payload=payload)
        after = self.get_network_status()
        after_mode = "DHCP" if after.get("dhcp") else "手动"
        if result.get("status") == "ok":
            result["message"] = f"已从 {before_mode} 切换为 {after_mode}"
        self._log("toggle network before: " + before_mode)
        self._log("toggle network payload: " + json.dumps(payload, ensure_ascii=False))
        self._log("toggle network result: " + json.dumps(result, ensure_ascii=False))
        self._log("toggle network after: " + json.dumps(after, ensure_ascii=False))
        return result

    def show_menu(self, event, view):
        menu = self.build_menu()
        NSMenu.popUpContextMenu_withEvent_forView_(menu, event, view)

    def build_menu(self):
        menu = NSMenu.alloc().initWithTitle_("鸟友工具箱")
        menu.setAutoenablesItems_(False)
        config = self._assistant_config()
        for item in config["menu_items"]:
            if item["action"] == "git_branches":
                menu.addItem_(self._build_git_menu_item(item))
            else:
                menu.addItem_(self._build_action_menu_item(item))
        return menu

    def _build_action_menu_item(self, item):
        title = item["label"]
        if item.get("action") == "toggle_network":
            title = self._network_toggle_title(title)
        menu_item = NSMenuItem.alloc().initWithTitle_action_keyEquivalent_(
            title, "assistantMenuAction:", ""
        )
        menu_item.setTarget_(self.menu_target)
        menu_item.setRepresentedObject_(item)
        menu_item.setEnabled_(True)
        return menu_item

    def _network_toggle_title(self, fallback):
        status = self.get_network_status()
        if status.get("error"):
            return f"{fallback}（状态未知）"
        mode = "DHCP" if status.get("dhcp") else "手动"
        target = "手动" if status.get("dhcp") else "DHCP"
        return f"{fallback}（当前：{mode}，切到{target}）"

    def _build_git_menu_item(self, item):
        root_item = NSMenuItem.alloc().initWithTitle_action_keyEquivalent_(item["label"], None, "")
        submenu = NSMenu.alloc().initWithTitle_(item["label"])
        status = self.get_git_status()
        if status.get("error"):
            error_item = NSMenuItem.alloc().initWithTitle_action_keyEquivalent_(
                status.get("error") or "Git 状态加载失败", None, ""
            )
            error_item.setEnabled_(False)
            submenu.addItem_(error_item)
        else:
            projects = status.get("projects") or []
            if len(projects) > 1:
                current_project = status.get("project_id")
                for project in projects:
                    project_item = NSMenuItem.alloc().initWithTitle_action_keyEquivalent_(
                        project.get("name") or project.get("id") or "项目", None, ""
                    )
                    project_item.setEnabled_(False)
                    if project.get("id") == current_project:
                        project_item.setState_(1)
                    submenu.addItem_(project_item)
                submenu.addItem_(NSMenuItem.separatorItem())

            current_branch = status.get("current_branch")
            project_id = status.get("project_id")
            for branch in status.get("branches") or []:
                branch_item = NSMenuItem.alloc().initWithTitle_action_keyEquivalent_(
                    str(branch), "assistantSwitchBranch:", ""
                )
                branch_item.setTarget_(self.menu_target)
                branch_item.setRepresentedObject_({"branch": str(branch), "project_id": project_id})
                branch_item.setEnabled_(True)
                if branch == current_branch:
                    branch_item.setState_(1)
                submenu.addItem_(branch_item)
            if not (status.get("branches") or []):
                empty_item = NSMenuItem.alloc().initWithTitle_action_keyEquivalent_("暂无分支", None, "")
                empty_item.setEnabled_(False)
                submenu.addItem_(empty_item)

        root_item.setSubmenu_(submenu)
        return root_item

    def _poll_config(self):
        while not self._stopped.wait(1.2):
            self._call_on_main(self._sync_from_config)

    def _call_on_main(self, func):
        try:
            AppHelper.callAfter(self._run_on_main, func)
        except Exception:
            self._log("callAfter failed\n" + traceback.format_exc())

    def _run_on_main(self, func):
        try:
            func()
        except Exception:
            self._log("main task failed\n" + traceback.format_exc())

    def _sync_from_config(self):
        config = self._assistant_config()
        signature = json.dumps(config, ensure_ascii=False, sort_keys=True)
        with self._lock:
            if signature == self._signature:
                return
            self._signature = signature
            if not config["enabled"]:
                self._destroy_window()
                return
            self._show_or_reload_window(config)

    def _assistant_config(self):
        raw = _manager.get_all().get("desktop_assistant") or {}
        merged = dict(ASSISTANT_DEFAULTS)
        merged.update(raw if isinstance(raw, dict) else {})
        icon_path = self._resolve_asset_path(str(merged.get("icon_path") or "").strip())
        emoji = str(merged.get("emoji") or ASSISTANT_DEFAULTS["emoji"]).strip()
        menu_items = []
        for item in merged.get("menu_items") or []:
            if not isinstance(item, dict) or item.get("enabled") is False:
                continue
            label = str(item.get("label") or "").strip()
            action = str(item.get("action") or "").strip()
            if not label or not action:
                continue
            menu_items.append({
                "label": label,
                "action": action,
                "target": str(item.get("target") or "").strip(),
                "enabled": True,
            })
        existing_actions = {item["action"] for item in menu_items}
        for item in ASSISTANT_DEFAULTS["menu_items"]:
            if item["action"] in {"start_service_vue", "open_service_vue_url"} and item["action"] not in existing_actions:
                menu_items.append(dict(item))
        if not menu_items:
            menu_items = ASSISTANT_DEFAULTS["menu_items"]
        return {
            "enabled": bool(merged.get("enabled")),
            "icon_path": icon_path,
            "emoji": emoji[:8] or ASSISTANT_DEFAULTS["emoji"],
            "menu_items": menu_items,
        }

    def _show_or_reload_window(self, config):
        self.current_icon_path = config.get("icon_path") or ""
        self.current_icon = self._load_icon(self.current_icon_path)
        self.current_emoji = config["emoji"]
        if self.window is None:
            self._log(
                "creating native assistant "
                f"icon={self.current_icon_path or '<emoji>'} emoji={self.current_emoji}"
            )
            frame = NSMakeRect(80, 760, ASSISTANT_SIZE, ASSISTANT_SIZE)
            self.window = NSWindow.alloc().initWithContentRect_styleMask_backing_defer_(
                frame,
                NSWindowStyleMaskBorderless,
                NSBackingStoreBuffered,
                False,
            )
            self.window.setLevel_(max(NSFloatingWindowLevel, NSStatusWindowLevel))
            self.window.setOpaque_(False)
            self.window.setBackgroundColor_(NSColor.clearColor())
            self.window.setHasShadow_(True)
            self.window.setReleasedWhenClosed_(False)
            self.window.setMovableByWindowBackground_(False)
            self.window.setIgnoresMouseEvents_(False)
            self.window.setCollectionBehavior_(
                NSWindowCollectionBehaviorCanJoinAllSpaces |
                NSWindowCollectionBehaviorFullScreenAuxiliary
            )
            self.view = AssistantEmojiView.alloc().initWithFrame_controller_(
                NSMakeRect(0, 0, ASSISTANT_SIZE, ASSISTANT_SIZE), self
            )
            self.menu_target = AssistantMenuTarget.alloc().initWithController_(self)
            self.window.setContentView_(self.view)
            self.window.setContentMinSize_(NSMakeSize(ASSISTANT_SIZE, ASSISTANT_SIZE))
            self.window.makeKeyAndOrderFront_(None)
            self.window.orderFrontRegardless()
            self._log("native assistant ordered front")
        else:
            if self.view is not None:
                self.view.setNeedsDisplay_(True)
            self.window.orderFrontRegardless()
            self._log("native assistant refreshed")

    def _destroy_window(self):
        with self._lock:
            if self.window is not None:
                try:
                    self.window.close()
                except Exception:
                    pass
                self.window = None
                self.view = None
                self.menu_target = None
                self._log("native assistant destroyed")

    def _open_path(self, path):
        self.main_window.load_url(f"{self.base_url}{path}")
        self.main_window.show()

    def _copy_text(self, text):
        subprocess.run(["pbcopy"], input=text, text=True, check=True)

    def _request_json(self, path, method="GET", payload=None):
        body = None
        headers = {"Content-Type": "application/json"}
        if payload is not None:
            body = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            f"{self.base_url}{path}",
            data=body,
            headers=headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                text = response.read().decode("utf-8", errors="replace")
                return json.loads(text) if text else {"status": "ok"}
        except urllib.error.HTTPError as exc:
            text = exc.read().decode("utf-8", errors="replace")
            try:
                data = json.loads(text)
            except ValueError:
                data = {"error": text or str(exc)}
            data.setdefault("status", "error")
            return data
        except Exception as exc:
            return {"status": "error", "error": str(exc)}

    def _log(self, message):
        try:
            with open(ASSISTANT_LOG_PATH, "a", encoding="utf-8") as f:
                f.write(str(message) + "\n")
        except Exception:
            pass

    def _resolve_asset_path(self, path):
        candidate = str(path or "").strip()
        if not candidate:
            return ""
        if os.path.isabs(candidate):
            return candidate
        roots = []
        if getattr(sys, "_MEIPASS", None):
            roots.append(sys._MEIPASS)
        roots.append(os.path.dirname(os.path.abspath(__file__)))
        for root in roots:
            resolved = os.path.abspath(os.path.join(root, candidate))
            if os.path.exists(resolved):
                return resolved
        return os.path.abspath(os.path.join(roots[-1], candidate))

    def _load_icon(self, path):
        icon_path = str(path or "").strip()
        if not icon_path or not os.path.exists(icon_path):
            return None
        try:
            image = NSImage.alloc().initWithContentsOfFile_(icon_path)
            return image if image is not None and image.isValid() else None
        except Exception:
            self._log("load icon failed\n" + traceback.format_exc())
            return None


def _draw_assistant_icon(view, bounds):
    image = getattr(view.controller, "current_icon", None)
    if image is None:
        return False
    inset = 1.0
    rect = NSMakeRect(
        bounds.origin.x + inset,
        bounds.origin.y + inset,
        bounds.size.width - inset * 2,
        bounds.size.height - inset * 2,
    )
    source_rect = NSMakeRect(0, 0, image.size().width, image.size().height)
    try:
        image.drawInRect_fromRect_operation_fraction_respectFlipped_hints_(
            rect,
            source_rect,
            NSCompositingOperationSourceOver,
            1.0,
            True,
            None,
        )
    except Exception:
        image.drawInRect_fromRect_operation_fraction_(
            rect,
            source_rect,
            NSCompositingOperationSourceOver,
            1.0,
        )
    return True
