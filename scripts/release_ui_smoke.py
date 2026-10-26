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
click('先看看示例')
wait('示例数据 · 非真实额度')
click('小组件')
wait('添加标准组件', scroll=True)
click('设置')
click('深色')
wait('后台目标间隔', scroll=True)
click('隐私与数据说明', scroll=True)
wait('关闭')
click('关闭')
click('退出示例模式', scroll=True)
click('清除')
click('额度')
wait('连接 Codex')
print('RELEASE_SMOKE_OK')
