#!/usr/bin/env python3
"""Validate readiness fields from a read-only Android thermal-service dump."""
import json
import re
import sys


def status_from_dump(text):
    fields = {}
    for name in ('IsStatusOverride', 'Thermal Status', 'HAL Ready'):
        matches = re.findall(r'^' + re.escape(name) + r':([^\r\n]*)\r?$', text, re.MULTILINE)
        if len(matches) != 1:
            raise ValueError('missing or duplicate thermal field: ' + name)
        fields[name] = matches[0].removeprefix(' ')
    if fields['IsStatusOverride'] != 'false' or fields['HAL Ready'] != 'true':
        raise ValueError('thermal service unavailable or overridden')
    if not re.fullmatch('[0-6]', fields['Thermal Status']):
        raise ValueError('invalid thermal severity')
    return int(fields['Thermal Status'])


if __name__ == '__main__':
    try:
        print(json.dumps({'schema': 'idle-thermal-status-v1',
                          'source': 'android-thermalservice',
                          'thermalStatus': status_from_dump(sys.stdin.read())}))
    except ValueError as error:
        sys.exit('thermal read refused: ' + str(error))
