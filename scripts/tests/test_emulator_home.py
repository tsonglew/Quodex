import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).parents[1]))
spec = importlib.util.spec_from_file_location('prepare_emulator_home', Path(__file__).parents[1] / 'prepare_emulator_home.py')
home = importlib.util.module_from_spec(spec)
spec.loader.exec_module(home)

LAUNCHER = 'com.android.launcher3'
COMPONENT = LAUNCHER + '/.uioverrides.QuickstepLauncher'


def screen(package=LAUNCHER, text='Home'):
    tree = ET.Element('hierarchy')
    ET.SubElement(tree, 'node', package=package, text=text)
    return tree


def anr(title, package='android'):
    tree = screen(package, title)
    ET.SubElement(tree, 'node', {'package': package, 'resource-id': 'android:id/aerr_wait',
                               'clickable': 'true', 'enabled': 'true', 'bounds': '[100,200][300,260]'})
    return tree


class EmulatorHomeTests(unittest.TestCase):
    def setUp(self):
        self.commands = []
        self.clock = 0
        self.focus = LAUNCHER

    def shell(self, *args):
        self.commands.append(args)
        if args == ('getprop', 'ro.kernel.qemu'):
            return '1'
        if args[:3] == ('cmd', 'package', 'resolve-activity'):
            return COMPONENT
        if args == ('pm', 'list', 'packages', '-s'):
            return 'package:' + LAUNCHER + '\npackage:com.android.systemui'
        if args == ('dumpsys', 'window'):
            return 'mCurrentFocus=Window{123 u0 ' + self.focus + '/.Home}'
        return ''

    def tick(self, seconds):
        self.clock += seconds

    def run_home(self, frames, timeout=12, shell=None):
        with patch.object(home.ui, 'shell', side_effect=shell or self.shell), \
                patch.object(home.ui, 'nodes', side_effect=frames), \
                patch.object(home, 'evidence') as capture, \
                patch.object(home.time, 'sleep', side_effect=self.tick), \
                patch.object(home.time, 'monotonic', side_effect=lambda: self.clock):
            home.prepare(timeout)
        return capture

    def test_stacked_boot_anrs_are_captured_then_home_must_be_stable(self):
        capture = self.run_home([anr("System UI isn't responding"), anr("Quickstep isn't responding"),
                                 screen(), screen(), screen()])
        self.assertEqual(2, capture.call_count)
        self.assertIn(('input', 'tap', '200', '230'), self.commands)
        self.assertEqual(1, self.commands.count(('am', 'force-stop', LAUNCHER)))
        self.assertEqual(3, self.commands.count(('dumpsys', 'window')))

    def test_repeated_system_failure_is_fatal(self):
        for title in ("Quickstep isn't responding", "System UI isn't responding"):
            with self.subTest(title=title), self.assertRaisesRegex(AssertionError, 'after one recovery'):
                self.run_home([anr(title), anr(title)])

    def test_app_and_unknown_system_dialogs_are_never_dismissed(self):
        for frame in (anr("Quodex isn't responding"), anr("Other launcher isn't responding"),
                      anr("System UI isn't responding", 'com.tsonglew.quodex')):
            self.commands.clear()
            self.clock = 0
            self.focus = 'android'
            with self.subTest(frame=ET.tostring(frame)), self.assertRaisesRegex(AssertionError, 'before timeout'):
                self.run_home([frame] * 3, timeout=3)
            self.assertFalse(any(c[:2] in [('input', 'tap'), ('am', 'force-stop')] for c in self.commands))

    def test_visible_home_without_focus_does_not_pass(self):
        self.focus = 'android'
        with self.assertRaisesRegex(AssertionError, 'before timeout'):
            self.run_home([screen()] * 3, timeout=3)

    def test_focus_without_visible_home_does_not_pass(self):
        with self.assertRaisesRegex(AssertionError, 'before timeout'):
            self.run_home([screen('android')] * 3, timeout=3)

    def test_unstable_home_resets_consecutive_successes(self):
        self.run_home([screen(), screen(), screen('android'), screen(), screen(), screen()])
        self.assertEqual(6, self.commands.count(('dumpsys', 'window')))

    def test_physical_device_or_installed_app_rejected_before_input(self):
        for guard, output in [(('getprop', 'ro.kernel.qemu'), '0'),
                              (('pm', 'list', 'packages', 'com.tsonglew.quodex'), 'package:com.tsonglew.quodex')]:
            self.commands.clear()
            def guarded(*args):
                result = self.shell(*args)
                return output if args == guard else result
            with self.subTest(guard=guard), self.assertRaises(AssertionError):
                self.run_home([], shell=guarded)
            self.assertFalse(any(c[0] in ('am', 'input') for c in self.commands))

    def test_wait_button_must_be_unique_enabled_and_have_real_bounds(self):
        for change in ('duplicate', 'disabled', 'empty_bounds'):
            tree = anr("System UI isn't responding")
            button = tree[-1]
            if change == 'duplicate':
                ET.SubElement(tree, 'node', button.attrib)
            elif change == 'disabled':
                button.set('enabled', 'false')
            else:
                button.set('bounds', '[0,0][0,0]')
            with self.subTest(change=change), self.assertRaises(AssertionError):
                home.wait_button(tree)

    def test_custom_home_or_non_system_launcher_rejected(self):
        for bad_command, response in [(('cmd', 'package', 'resolve-activity'), 'custom.home/.Home'),
                                       (('pm', 'list', 'packages', '-s'), 'package:com.android.systemui')]:
            self.commands.clear()
            def unexpected(*args):
                result = self.shell(*args)
                return response if args[:len(bad_command)] == bad_command else result
            with self.subTest(command=bad_command), self.assertRaises(AssertionError):
                self.run_home([], shell=unexpected)
            self.assertFalse(any(c[0] in ('am', 'input') for c in self.commands))
