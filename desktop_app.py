import socket
import threading
import time

import webview

from app import app, init_bookmarks_table, warmup_caches
from config import PORT
from desktop_assistant import DesktopAssistantController


def _find_available_port(preferred_port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind(("127.0.0.1", preferred_port))
            return preferred_port
        except OSError:
            pass

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def _run_server(port):
    warmup_caches()
    init_bookmarks_table()
    app.run(host="127.0.0.1", port=port, debug=False, use_reloader=False)


if __name__ == "__main__":
    port = _find_available_port(PORT)
    base_url = f"http://127.0.0.1:{port}"
    server = threading.Thread(target=_run_server, args=(port,), daemon=True)
    server.start()
    time.sleep(1)

    main_window = webview.create_window(
        "鸟友工具箱",
        base_url,
        width=1280,
        height=860,
        min_size=(1024, 680),
        text_select=True,
    )
    assistant = DesktopAssistantController(main_window, base_url)
    assistant.prepare_initial_window()
    webview.start(assistant.start)
