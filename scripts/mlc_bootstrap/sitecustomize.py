"""Keep the isolated Windows DLL search directory alive in MLC subprocesses."""
import os

_handles = []
if os.name == "nt" and os.environ.get("WEBTOON_MLC_DLL_DIR"):
    _handles.append(os.add_dll_directory(os.environ["WEBTOON_MLC_DLL_DIR"]))
