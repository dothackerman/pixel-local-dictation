import unittest
from thermal_status import status_from_dump


def fixture(status=0):
    return f'IsStatusOverride: false\nThermal Status: {status}\nHAL Ready: true\n'


class StatusTests(unittest.TestCase):
    def test_all_defined_severities_are_retained(self):
        for status in range(7):
            self.assertEqual(status_from_dump(fixture(status)), status)

    def test_crlf_and_unrelated_dump_fields(self):
        self.assertEqual(status_from_dump('listeners: 4\n' + fixture(1).replace('\n', '\r\n')), 1)

    def test_missing_duplicate_invalid_and_overridden_fail_closed(self):
        invalid = ['', fixture().replace('HAL Ready: true', 'HAL Ready: false'),
                   fixture().replace('IsStatusOverride: false', 'IsStatusOverride: true'),
                   fixture() + 'Thermal Status: 0\n',
                   fixture() + 'Thermal Status:\n',
                   fixture() + 'HAL Ready:\n',
                   fixture() + 'IsStatusOverride:\n',
                   fixture().replace('Thermal Status: 0\n', ''),
                   fixture(7), fixture(-1), fixture('0 extra'), fixture('true')]
        for text in invalid:
            with self.subTest(text=text), self.assertRaises(ValueError):
                status_from_dump(text)


if __name__ == '__main__':
    unittest.main()
