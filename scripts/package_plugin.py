#!/usr/bin/env python3
"""Build a cross-platform AstrBot plugin archive.

AstrBot 4.23.6 assumes that the first ZIP member is the plugin's top-level
directory. Some Windows archive tools omit directory members, which makes the
legacy installer treat the first Python file as a directory. This packager
always writes the directory first and always uses POSIX path separators.
"""

from __future__ import annotations

import argparse
import zipfile
from pathlib import Path


PLUGIN_DIR = "astrbot_plugin_mineastr"
PACKAGE_FILES = (
    "__init__.py",
    "_conf_schema.json",
    "aqqbot_compat.py",
    "main.py",
    "metadata.yaml",
    "minecraft_adapter.py",
    "requirements.txt",
    "logo.png",
    "README.md",
    "PROTOCOL.md",
    "AQQBOT_MIGRATION.md",
    "THIRD_PARTY_NOTICES.md",
    "CHANGELOG.md",
    "LICENSE",
)
ZIP_TIMESTAMP = (2026, 1, 1, 0, 0, 0)


def _directory_info(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, ZIP_TIMESTAMP)
    info.create_system = 3
    info.external_attr = (0o40755 << 16) | 0x10
    info.compress_type = zipfile.ZIP_STORED
    return info


def _file_info(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, ZIP_TIMESTAMP)
    info.create_system = 3
    info.external_attr = 0o100644 << 16
    info.compress_type = zipfile.ZIP_DEFLATED
    return info


def build_archive(repo_root: Path, output: Path) -> None:
    missing = [name for name in PACKAGE_FILES if not (repo_root / name).is_file()]
    if missing:
        raise FileNotFoundError("缺少插件文件：" + ", ".join(missing))

    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr(_directory_info(f"{PLUGIN_DIR}/"), b"")
        for name in PACKAGE_FILES:
            archive_name = f"{PLUGIN_DIR}/{Path(name).as_posix()}"
            archive.writestr(_file_info(archive_name), (repo_root / name).read_bytes())

    with zipfile.ZipFile(output, "r") as archive:
        members = archive.namelist()
    if not members or members[0] != f"{PLUGIN_DIR}/":
        raise RuntimeError("ZIP 首条不是 AstrBot 插件目录。")
    if any("\\" in member for member in members):
        raise RuntimeError("ZIP 中出现了 Windows 反斜杠路径。")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "output",
        nargs="?",
        type=Path,
        default=Path("dist") / "astrbot_plugin_mineastr-v0.6.12.zip",
    )
    args = parser.parse_args()
    repo_root = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    build_archive(repo_root, output)
    print(f"已生成 AstrBot 兼容安装包：{output}")


if __name__ == "__main__":
    main()
