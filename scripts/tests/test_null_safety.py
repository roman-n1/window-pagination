import subprocess
import tempfile
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_null_safety import violations

class NullSafetyTest(unittest.TestCase):
    def test_nullable_types_nested_generics_and_functions(self):
        for source in ('val a: String?', 'val a: List<Map<String, Int?>>',
                       'val a: (() -> Unit)?', 'typealias X = String?',
                       'fun <T : Any?> f() {}', 'val x: List<String /* note */ ?>'):
            with self.subTest(source=source):
                self.assertTrue(violations(source))

    def test_null_and_unsafe_operators(self):
        for source in ('val a = null', 'a!!', 'a?.x', 'a ?: b', 'a as? String'):
            with self.subTest(source=source):
                self.assertTrue(violations(source))

    def test_comments_strings_and_characters_are_not_code(self):
        source = '''// String? null !!
/* nested /* null */ String? */
val docs = "String? null !! ${safe}"
val raw = """null? !!"""
val char = '?'
val ordinary = "null-safe"
'''
        self.assertEqual([], violations(source))

    def test_interpolation_is_executable_code(self):
        for source in ('val s = "${null}"', 'val s = """${x?.name}"""',
                       'val s = "${f { x!! }}"', 'val s = "${"${null}"}"'):
            with self.subTest(source=source):
                self.assertTrue(violations(source))

    def test_reports_correct_line(self):
        self.assertEqual(3, violations('// null\nval x = "?"\nval y: String?')[0][0])

    def test_named_states_are_valid(self):
        self.assertEqual([], violations('sealed interface Focus { data object None : Focus }'))

    def test_nullable_producers_and_assertions(self):
        for source in ('items.firstOrNull()', 'items.singleOrNull()', 'requireNotNull(x)', 'checkNotNull(x)', 'assertNotNull(x)', 'items.`firstOrNull`()'):
            with self.subTest(source=source):
                self.assertTrue(violations(source))


    def test_cli_checks_new_modules_tests_and_gradle_scripts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in ('new-module/src/commonMain/Bad.kt',
                             'new-module/src/commonTest/Bad.kt', 'build.gradle.kts'):
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('@Suppress("NoNull")\nval value: String? = null')
            result = subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1] / 'check_null_safety.py'), str(root)], capture_output=True, text=True)
            self.assertEqual(1, result.returncode)
            self.assertIn('3 Kotlin files, 6 violations', result.stdout)

    def test_empty_scan_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1] / 'check_null_safety.py'), directory], capture_output=True, text=True)
            self.assertEqual(1, result.returncode)

if __name__ == '__main__':
    unittest.main()
