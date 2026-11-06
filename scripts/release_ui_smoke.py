"""Exercise the actual minified APK through platform UI, without app/test class coupling."""
import re
from pathlib import Path
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

adb = None
WINDOW_XML = '/sdcard/quodex-release-window.xml'


def shell(*args):
    return subprocess.check_output([adb, '-e', 'shell', *args], text=True, timeout=20)


def nodes():
    # UIAutomator can exit successfully with a null root during window transitions.
    # Never let that turn an old dump into coordinates for a different screen.
    last_error = None
    for attempt in range(6):
        try:
            shell('rm', '-f', WINDOW_XML)
            result = shell('uiautomator', 'dump', WINDOW_XML)
            if 'ERROR:' in result:
                raise ValueError(result.strip())
            tree = ET.fromstring(shell('cat', WINDOW_XML))
            if tree.tag != 'hierarchy' or next(tree.iter('node'), None) is None:
                raise ValueError('UIAutomator returned an empty hierarchy')
            return tree
        except (subprocess.SubprocessError, ET.ParseError, ValueError) as error:
            last_error = error
            print(f'RELEASE_UI_DUMP_RETRY {attempt + 1}/6: {error}', flush=True)
            if attempt < 5:
                time.sleep(.5)
    raise AssertionError('Cannot obtain a fresh release UI hierarchy after 6 attempts') from last_error


def capture_failure(output=Path('screenshots')):
    output.mkdir(exist_ok=True)
    # Capture independently: a broken accessibility bridge must not hide the screen.
    captures = {
        'release-ui-failure.png': lambda: subprocess.check_output(
            [adb, '-e', 'exec-out', 'screencap', '-p'], timeout=20),
        'release-ui-failure.xml': lambda: ET.tostring(nodes()),
        'release-ui-activities.txt': lambda: shell('dumpsys', 'activity', 'activities').encode(),
    }
    for name, capture in captures.items():
        try:
            (output / name).write_bytes(capture())
        except Exception as error:
            print(f'Cannot capture {name}: {error}', file=sys.stderr)


def find(text, tree=None):
    tree = nodes() if tree is None else tree
    return next((node for node in tree.iter('node') if node.attrib.get('text') == text), None)


def bounds(node):
    values = tuple(map(int, re.findall(r'-?\d+', node.attrib.get('bounds', ''))))
    return values if len(values) == 4 else (0, 0, 0, 0)


def navigation_items(tree):
    # Match all three tabs, including the selected tab (Android marks it non-clickable).
    # Headings may repeat a tab's text; the bottommost instance is the dock item.
    candidates = {}

    def visit(node, clickable=None):
        if node.attrib.get('clickable') == 'true' or node.attrib.get('selected') == 'true':
            clickable = node
        text = node.attrib.get('text')
        if text and clickable is not None:
            candidates.setdefault(text, []).append((node, clickable))
        for child in node:
            visit(child, clickable)

    visit(tree)
    for labels in [('Overview', 'Accounts', 'Settings'), ('总览', '账号', '设置'),
                   ('概要', 'アカウント', '設定'),
                   ('Quota', 'Widgets', 'Settings'), ('额度', '小组件', '设置'),
                   ('使用枠', 'ウィジェット', '設定')]:
        if all(label in candidates for label in labels):
            items = [max(candidates[label], key=lambda pair: bounds(pair[1])[3]) for label in labels]
            rectangles = [bounds(item) for _, item in items]
            if max(rect[1] for rect in rectangles) < min(rect[3] for rect in rectangles):
                return items
    return []


def covered_by_navigation(node, tree):
    x1, y1, x2, y2 = bounds(node)
    if x1 >= x2 or y1 >= y2:
        return True
    items = navigation_items(tree)
    if any(label is node for label, _ in items):
        return False
    # A floating sibling can cover a node still reported by UI Automator.
    # Move the target above the dock before sending a real touch event.
    return bool(items) and y2 > min(bounds(item)[1] for _, item in items)


def wait(text, scroll=False, for_tap=False):
    attempts = 13 if scroll else 6
    for attempt in range(attempts):
        tree = nodes()
        node = find(text, tree)
        covered = node is not None and for_tap and covered_by_navigation(node, tree)
        if node is not None and not covered:
            return node
        if attempt == attempts - 1:
            break
        if covered:
            print(f'RELEASE_UI_SCROLL_ABOVE_DOCK: {text}', flush=True)
        if scroll or covered:
            dimensions = list(map(int, re.findall(r'\d+', shell('wm', 'size'))[-2:]))
            width, height = dimensions
            shell('input', 'swipe', str(width // 2), str(height * 3 // 4), str(width // 2), str(height // 3), '300')
        time.sleep(.2)
    raise AssertionError(f'Release UI missing: {text}')


def click(text, scroll=False):
    node = wait(text, scroll, for_tap=True)
    x1, y1, x2, y2 = bounds(node)
    print(f'RELEASE_UI_CLICK: {text}', flush=True)
    shell('input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


def click_tab(text):
    # Page headings can repeat the tab label; always tap the actual navigation item.
    tree = nodes()
    item = next((item for label, item in navigation_items(tree) if label.attrib.get('text') == text), None)
    if item is None:
        raise AssertionError(f'Release navigation missing: {text}')
    x1, y1, x2, y2 = bounds(item)
    print(f'RELEASE_UI_TAB: {text}', flush=True)
    shell('input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


def open_settings(destination, tab='Settings'):
    click_tab(tab)
    # Reselecting the current destination returns from any saved detail to its index.
    click_tab(tab)
    click(destination, scroll=True)


def run():
    shell('am', 'start', '-W', '-n', 'com.tsonglew.quodex/.MainActivity')
    click('Explore a demo')
    wait('Sample data · Not your actual quota')
    open_settings('Home screen')
    wait('Add standard widget', scroll=True)
    open_settings('App language')
    click('日本語', scroll=True)
    wait('アプリの言語')
    shell('am', 'force-stop', 'com.tsonglew.quodex')
    shell('am', 'start', '-W', '-n', 'com.tsonglew.quodex/.MainActivity')
    click_tab('概要')
    wait('サンプルデータ · 実際の使用枠ではありません')
    open_settings('アプリの言語', tab='設定')
    click('简体中文', scroll=True)
    wait('应用语言')
    click('English', scroll=True)
    wait('App language')
    open_settings('Appearance')
    click('Dark', scroll=True)
    open_settings('Background refresh')
    wait('Target background interval', scroll=True)
    open_settings('Help and privacy')
    click('Privacy and data', scroll=True)
    wait('Close')
    click('Close')
    click_tab('Accounts')
    click('Codex')
    click('Exit demo mode', scroll=True)
    click('Clear')
    click_tab('Overview')
    wait('Connect Codex')
    print('RELEASE_SMOKE_OK')


if __name__ == '__main__':
    adb = sys.argv[1]
    try:
        run()
    except Exception:
        capture_failure()
        raise
