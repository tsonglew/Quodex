"""Exercise the actual minified APK through platform UI, without app/test class coupling."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

adb = sys.argv[1]


def shell(*args):
    return subprocess.check_output([adb, '-e', 'shell', *args], text=True, timeout=20)


def nodes():
    shell('uiautomator', 'dump', '/sdcard/quodex-release-window.xml')
    return ET.fromstring(shell('cat', '/sdcard/quodex-release-window.xml')).iter('node')


def find(text):
    return next((node for node in nodes() if node.attrib.get('text') == text), None)


def wait(text, scroll=False):
    for attempt in range(6):
        node = find(text)
        if node is not None:
            return node
        if scroll:
            dimensions = list(map(int, re.findall(r'\d+', shell('wm', 'size'))[-2:]))
            width, height = dimensions
            shell('input', 'swipe', str(width // 2), str(height * 3 // 4), str(width // 2), str(height // 3), '300')
        time.sleep(.2)
    raise AssertionError(f'Release UI missing: {text}')


def click(text, scroll=False):
    node = wait(text, scroll)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
    shell('input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


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
