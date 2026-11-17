"""Host side of the app's LabReceipts rule (#64): a full files/lab receipt file rotates to
NAME.prev.jsonl, one older generation, so a pulled receipt may come as two files."""
from pathlib import Path


def generations(path) -> list[Path]:
    """PATH preceded by its NAME.prev.SUFFIX sibling when that was pulled too: older rows first."""
    path = Path(path)
    previous = path.with_name(f"{path.stem}.prev{path.suffix}")
    return [previous, path] if previous.is_file() else [path]
