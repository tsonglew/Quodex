"""Exercise the actual minified APK through platform UI, without app/test class coupling."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

adb = None


def shell(*args):
    return subprocess.check_output([adb, '-e', 'shell', *args], text=True, timeout=20)


def nodes():
    shell('uiautomator', 'dump', '/sdcard/quodex-release-window.xml')
    return ET.fromstring(shell('cat', '/sdcard/quodex-release-window.xml'))


def find(text, tree=None):
    tree = nodes() if tree is None else tree
    return next((node for node in tree.iter('node') if node.attrib.get('text') == text), None)


def bounds(node):
    values = tuple(map(int, re.findall(r'-?\d+', node.attrib.get('bounds', ''))))
    return values if len(values) == 4 else (0, 0, 0, 0)


def navigation_items(tree):
    # Match all three localized tabs, including their clickable parent bounds.
    # Headings may repeat a tab's text; the bottommost instance is the dock item.
    candidates = {}

    def visit(node, clickable=None):
        if node.attrib.get('clickable') == 'true':
            clickable = node
        text = node.attrib.get('text')
        if text and clickable is not None:
            candidates.setdefault(text, []).append((node, clickable))
        for child in node:
            visit(child, clickable)

    visit(tree)
    for labels in [('Quota', 'Widgets', 'Settings'), ('额度', '小组件', '设置'),
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
    shell('input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


def run():
    shell('am', 'start', '-W', '-n', 'com.tsonglew.quodex/.MainActivity')
    click('Explore a demo')
    wait('Sample data · Not your actual quota')
    click('Widgets')
    wait('Add standard widget', scroll=True)
    click('Settings')
    click('日本語', scroll=True)
    wait('アプリの言語')
    shell('am', 'force-stop', 'com.tsonglew.quodex')
    shell('am', 'start', '-W', '-n', 'com.tsonglew.quodex/.MainActivity')
    wait('サンプルデータ · 実際の使用枠ではありません')
    click('設定')
    click('简体中文', scroll=True)
    wait('应用语言')
    click('English', scroll=True)
    wait('App language')
    click('Dark', scroll=True)
    wait('Target background interval', scroll=True)
    click('Privacy and data', scroll=True)
    wait('Close')
    click('Close')
    click('Exit demo mode', scroll=True)
    click('Clear')
    click('Quota')
    wait('Connect Codex')
    print('RELEASE_SMOKE_OK')


if __name__ == '__main__':
    adb = sys.argv[1]
    try:
        run()
    except Exception:
        # Preserve the actual foreground UI when a release-only click fails.
        from pathlib import Path
        try:
            output = Path('screenshots')
            output.mkdir(exist_ok=True)
            tree = nodes()
            (output / 'release-ui-failure.xml').write_bytes(ET.tostring(tree))
            (output / 'release-ui-failure.png').write_bytes(subprocess.check_output(
                [adb, '-e', 'exec-out', 'screencap', '-p'], timeout=20))
        except Exception as diagnostic_error:
            print(f'Cannot capture release failure UI: {diagnostic_error}', file=sys.stderr)
        raise
