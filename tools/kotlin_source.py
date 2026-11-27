"""Kotlin and Java source as the host checks read it (storage_guard, device_classes)."""
import re


def code(source):
    """Source with comments blanked, line structure kept; strings stay."""
    blank = lambda match: re.sub(r'[^\n]', ' ', match.group(0))
    return re.sub(r'(?<![:"\w])//[^\n]*', '', re.sub(r'/\*.*?\*/', blank, source, flags=re.S))
