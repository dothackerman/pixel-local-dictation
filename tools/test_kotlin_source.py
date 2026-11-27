"""Kotlin and Java source without comments, as the host checks read it."""
import unittest

import kotlin_source


class CodeTests(unittest.TestCase):
    def test_comments_go_lines_and_strings_stay(self):
        source = ('val a = 1 // ProcessBuilder\n'
                  '/* UiDevice\n   exec( */ val b = "http://x"\n'
                  'val c = "a//b"\n')
        self.assertEqual('val a = 1 \n' + ' ' * 11 + '\n' + ' ' * 11 + ' val b = "http://x"\nval c = "a//b"\n',
                         kotlin_source.code(source))


if __name__ == '__main__':
    unittest.main()
