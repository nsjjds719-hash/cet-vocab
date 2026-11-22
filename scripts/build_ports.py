"""Build the Windows portable app."""

import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build"
VERSION = "0.1.8"


def assets(out):
    subprocess.run([sys.executable, str(ROOT / "scripts" / "build_shared.py"), str(out)],
                   check=True)


def archive(directory, name):
    path = shutil.make_archive(str(BUILD / name), "zip", directory.parent, directory.name)
    print(path, flush=True)


def windows():
    directory = BUILD / f"cijian-windows-x64-{VERSION}"
    project = ROOT / "windows" / "Cijian.Windows.csproj"
    settings = ["-r", "win-x64", "-p:SelfContained=true"]
    subprocess.run(["dotnet", "restore", str(project), *settings,
                    "--configfile", str(project.with_name("NuGet.Config"))], check=True)
    subprocess.run(["dotnet", "publish", str(project), *settings, "--no-restore",
                    "-c", "Release", "-o", str(directory)], check=True)
    assets(directory / "web")
    # 项目根目录的使用说明随包附上；标题里的版本号按本次构建改写，带 BOM 方便记事本识别。
    guide = (ROOT / "使用说明.txt").read_text(encoding="utf-8-sig")
    guide = re.sub(r"（\d+\.\d+\.\d+）", f"（{VERSION}）", guide, count=1)
    (directory / "使用说明.txt").write_text(guide, encoding="utf-8-sig")
    archive(directory, f"Cijian-Windows-x64-{VERSION}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("platform", choices=("windows", "all"), nargs="?", default="all")
    arguments = parser.parse_args()
    BUILD.mkdir(exist_ok=True)
    if arguments.platform in ("windows", "all"):
        windows()


if __name__ == "__main__":
    main()
