import os


def main() -> int:
    """Print the home directory."""
    print(os.path.expanduser("~"))
    return 0
