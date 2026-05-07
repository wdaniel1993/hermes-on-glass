try:
    from .adapter import register
except ImportError:  # standalone (tests put the dir on sys.path)
    from adapter import register  # type: ignore[no-redef]

__all__ = ["register"]
