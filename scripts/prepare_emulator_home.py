"""Establish a responsive system home before installing the App under test."""
from pathlib import Path
import re
import subprocess
import sys
import time

import release_ui_smoke as ui

LAUNCHERS = {
    'com.android.launcher3': "Quickstep isn't responding",
    'com.google.android.apps.nexuslauncher': "Pixel Launcher isn't responding",
}


def boot_dialog(tree, launcher):
    titles = {node.get('text', '').replace('’', "'") for node in tree.iter('node')
              if node.get('package') == 'android'}
    if "System UI isn't responding" in titles:
        return 'com.android.systemui'
    if LAUNCHERS[launcher] in titles:
        return launcher
    return None


def wait_button(tree):
    buttons = [node for node in tree.iter('node')
               if node.get('package') == 'android' and node.get('resource-id') == 'android:id/aerr_wait'
               and node.get('clickable') == 'true' and node.get('enabled') == 'true']
    if len(buttons) != 1:
        raise AssertionError('Known boot ANR has no unambiguous Wait button')
    x1, y1, x2, y2 = ui.bounds(buttons[0])
    if x1 >= x2 or y1 >= y2:
        raise AssertionError('Boot ANR Wait button has invalid bounds')
    return str((x1 + x2) // 2), str((y1 + y2) // 2)


def evidence(name, tree=None):
    output = Path('screenshots')
    output.mkdir(exist_ok=True)
    if tree is not None:
        (output / f'{name}.xml').write_bytes(ui.ET.tostring(tree))
    captures = {
        'png': lambda: subprocess.check_output([ui.adb, '-e', 'exec-out', 'screencap', '-p'], timeout=20),
        'log': lambda: ui.shell('logcat', '-d', '-s', 'ActivityManager', 'WindowManager').encode(),
    }
    for extension, capture in captures.items():
        try:
            (output / f'{name}.{extension}').write_bytes(capture())
        except (subprocess.SubprocessError, OSError) as error:
            print(f'Cannot capture boot evidence: {error}', file=sys.stderr)


def prepare(timeout=120):
    if ui.shell('getprop', 'ro.kernel.qemu').strip() != '1':
        raise AssertionError('Home preparation is only allowed on a disposable emulator')
    if ui.shell('pm', 'list', 'packages', 'com.tsonglew.quodex').strip():
        raise AssertionError('Home preparation must run before installing the App/test packages')
    component = ui.shell('cmd', 'package', 'resolve-activity', '--brief',
                         '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME').strip().splitlines()[-1]
    launcher = component.split('/')[0]
    if launcher not in LAUNCHERS or not re.fullmatch(r'[\w.]+/[\w.$]+', component):
        raise AssertionError(f'Unexpected emulator home: {component}')
    system_packages = set(ui.shell('pm', 'list', 'packages', '-s').splitlines())
    if not {f'package:{launcher}', 'package:com.android.systemui'} <= system_packages:
        raise AssertionError('Home and System UI must be system packages')

    ui.shell('am', 'start', '-W', '-n', component)
    deadline = time.monotonic() + timeout
    recovered = set()
    stable = 0
    while time.monotonic() < deadline:
        tree = ui.nodes()
        dialog = boot_dialog(tree, launcher)
        if dialog:
            evidence(f'boot-anr-{dialog}', tree)
            if dialog in recovered:
                raise AssertionError(f'System startup remains unhealthy after one recovery: {dialog}')
            recovered.add(dialog)
            if dialog == launcher:
                # A boot-time input timeout can leave a dialog queued for minutes.
                # Restart only this known system home, before any App code runs.
                ui.shell('am', 'force-stop', launcher)
                ui.shell('am', 'start', '-W', '-n', component)
            else:
                ui.shell('input', 'tap', *wait_button(tree))
            print(f'BOOT_SYSTEM_RECOVERY {dialog}', flush=True)
            stable = 0
        else:
            try:
                focused = ui.shell('dumpsys', 'window')
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
                # An observation may briefly fail while a recovered home binds.
                # Mutating recovery commands and isolation checks remain fail-fast.
                print(f'BOOT_FOCUS_NOT_READY {type(error).__name__}', flush=True)
                stable = 0
                time.sleep(1)
                continue
            home_visible = any(node.get('package') == launcher for node in tree.iter('node'))
            home_focused = re.search(r'mCurrentFocus=.*\b' + re.escape(launcher) + r'/', focused)
            stable = stable + 1 if home_visible and home_focused else 0
            if stable >= 3:
                print('EMULATOR_HOME_READY', flush=True)
                return
        time.sleep(1)
    raise AssertionError('Emulator home did not become visible and focused before timeout')


if __name__ == '__main__':
    ui.adb = sys.argv[1]
    ui.WINDOW_XML = '/sdcard/quodex-boot-window.xml'
    try:
        prepare()
    except Exception:
        evidence('boot-home-failure')
        raise
