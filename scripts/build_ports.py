"""Build the Windows portable app and the self-contained iOS Xcode project."""

import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build"
VERSION = "0.1.3"


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
    (directory / "使用说明.txt").write_text(
        "词间 Windows x64\n\n完整解压后打开 Cijian.exe。请保留同目录下的文件和 web 文件夹。\n"
        "如提示缺少 WebView2，请安装 Microsoft Edge WebView2 Runtime。\n"
        "词库随应用提供，学习记录保存在本机用户目录，不与其他设备同步。\n",
        encoding="utf-8-sig")
    archive(directory, f"Cijian-Windows-x64-{VERSION}")


def icon(path):
    from PIL import Image, ImageDraw

    image = Image.new("RGB", (1024, 1024), "#264b3c")
    draw = ImageDraw.Draw(image)
    draw.polygon([(237, 300), (350, 279), (472, 316), (484, 349), (484, 730),
                  (372, 689), (237, 706)], fill="#f8f6ef")
    draw.polygon([(540, 349), (552, 316), (674, 279), (787, 300), (787, 706),
                  (652, 689), (540, 730)], fill="#f8f6ef")
    draw.polygon([(640, 290), (720, 285), (720, 496), (680, 464), (640, 496)], fill="#f3cb87")
    image.save(path, optimize=True)


def ios():
    with tempfile.TemporaryDirectory(prefix="cijian-ios-") as workspace:
        project = Path(workspace) / "Cijian-iOS"
        shutil.copytree(ROOT / "ios", project, ignore=shutil.ignore_patterns("xcuserdata", "web"))
        assets(project / "web")
        icon(project / "Assets.xcassets" / "AppIcon.appiconset" / "AppIcon.png")
        (project / "使用说明.txt").write_text(
            "词间 iOS 工程\n\n"
            "在 Mac 上解压，使用 Xcode 打开 Cijian.xcodeproj。最低系统为 iOS 16。\n"
            "模拟器可选择 Cijian 后直接运行。连接 iPhone 调试时，进入 Signing & Capabilities，"
            "选择自己的 Team，必要时自定义 Bundle Identifier，再选择连接的设备并运行。\n"
            "离线词库在 web 文件夹中，已加入应用资源。学习进度保存在本机，与其他设备分别记录。\n"
            "Windows 已校验共享学习流程和工程资源。iOS 编译、签名及真机运行需在 Mac 上完成。\n",
            encoding="utf-8")
        archive(project, f"Cijian-iOS-Xcode-{VERSION}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("platform", choices=("windows", "ios", "all"), nargs="?", default="all")
    arguments = parser.parse_args()
    BUILD.mkdir(exist_ok=True)
    if arguments.platform in ("windows", "all"):
        windows()
    if arguments.platform in ("ios", "all"):
        ios()


if __name__ == "__main__":
    main()
