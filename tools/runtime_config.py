"""Explicit CPU session controls; defaults preserve the stock provider."""
import tempfile
from pathlib import Path

# Keep configuration paths alive for the process lifetime. Profiles belong in the run directory.
_configs = []
_profile_dir = None

def set_profile_directory(path):
    global _profile_dir
    _profile_dir = path


def provider(v, role="model", profile_dir=None):
    profile_dir = profile_dir or _profile_dir
    settings = v["runtime"]
    if settings.get("encoder_provider", "cpu") != "cpu":
        raise ValueError("a WebGPU encoder runs only in the patched phone build, never on the laptop")
    arena, pattern = settings[role + "_arena"], settings[role + "_pattern"]
    if arena and pattern and not settings["profile"]:
        return "cpu"
    if settings["profile"] and profile_dir is None:
        raise ValueError("profiling requires an explicit run profile directory")
    config = tempfile.TemporaryDirectory(prefix="dictation-session-")
    _configs.append(config)
    path = Path(config.name) / (role + ".config")
    lines = [f"EnableCpuMemArena={int(arena)}", f"EnableMemPattern={int(pattern)}"]
    if settings["profile"]:
        destination = Path(profile_dir).resolve()
        destination.mkdir(parents=True, exist_ok=True)
        lines.append("ProfilingFilePrefix=" + str(destination / role))
    path.write_text("\n".join(lines) + "\n")
    return "cpu:" + str(path)
