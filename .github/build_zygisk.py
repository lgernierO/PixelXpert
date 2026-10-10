#!/usr/bin/env python3
"""按固定的 LSPlant 工具链构建双 ABI Zygisk 库，失败时不更新模块目录。"""
import argparse
import hashlib
from pathlib import Path
import shutil
import struct
import subprocess
import urllib.request
import zipfile

NDK_VERSION = "30.0.16248370"
LIBCXX_SHA256 = "bbe93ec17cf7cbe3d6d7dd305a645b4b61d7afc09dc048dc7db53149efb65e6e"
PREFAB_SHA256 = "e219c8cd6bfd9ff71503a57c6af34b9ba060f03525cc3e58330dee53245a5ed6"


def download(url, path, expected):
    if not path.exists():
        request = urllib.request.Request(url, headers={"User-Agent": "PixelXpert-build"})
        partial = path.with_suffix(path.suffix + ".part")
        with urllib.request.urlopen(request, timeout=180) as response, partial.open("wb") as output:
            shutil.copyfileobj(response, output)
        partial.replace(path)
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise RuntimeError(f"构建依赖校验失败：{path.name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--jobs", type=int, default=2)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    ndk = args.ndk.resolve()
    if f"Pkg.Revision = {NDK_VERSION}" not in (ndk / "source.properties").read_text():
        raise RuntimeError(f"此 LSPlant/libcxx 组合要求 NDK {NDK_VERSION}")
    work = args.work_dir.resolve()
    downloads = work / "downloads"
    downloads.mkdir(parents=True, exist_ok=True)
    cxx_aar = downloads / f"libcxx-full-{NDK_VERSION}.aar"
    prefab_cli = downloads / "prefab-cli.jar"
    download(f"https://repo.maven.apache.org/maven2/org/lsposed/libcxx/libcxx-full/{NDK_VERSION}/libcxx-full-{NDK_VERSION}.aar", cxx_aar, LIBCXX_SHA256)
    download("https://dl.google.com/dl/android/maven2/com/google/prefab/cli/2.1.0/cli-2.1.0-all.jar", prefab_cli, PREFAB_SHA256)
    cxx = work / "toolchains/libcxx-full"
    if not (cxx / "prefab/prefab.json").is_file():
        with zipfile.ZipFile(cxx_aar) as archive:
            archive.extractall(cxx)
    tools = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    artifacts = []
    for abi, short, machine in [("arm64-v8a", "arm64", 183), ("armeabi-v7a", "arm", 40)]:
        prefix = work / f"toolchains/prefab-{short}"
        build = work / f"native-{short}"
        subprocess.run(["java", "--enable-native-access=ALL-UNNAMED", "-jar", str(prefab_cli), "--build-system", "cmake", "--platform", "android", "--abi", abi, "--os-version", "24", "--stl", "c++_static", "--ndk-version", "30", "--output", str(prefix), str(cxx / "prefab")], check=True)
        subprocess.run(["cmake", "-S", str(root / "zygisk/src"), "-B", str(build), "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release", f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake", f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-24", "-DANDROID_STL=c++_static", f"-DCMAKE_FIND_ROOT_PATH={prefix}", "-DCMAKE_C_COMPILER_LAUNCHER=", "-DCMAKE_CXX_COMPILER_LAUNCHER="], check=True)
        subprocess.run(["cmake", "--build", str(build), "--target", "pixelxpert_zygisk", "-j", str(args.jobs)], check=True)
        library = build / "libpixelxpert_zygisk.so"
        subprocess.run([str(tools / "llvm-strip"), "--strip-all", str(library)], check=True)
        header = library.read_bytes()[:20]
        if header[:4] != b"\x7fELF" or struct.unpack_from("<H", header, 18)[0] != machine:
            raise RuntimeError(f"ELF 架构错误：{abi}")
        needed = subprocess.check_output([str(tools / "llvm-readelf"), "-d", str(library)], text=True)
        if "libc++_shared.so" in needed or "libcxx.so" in needed:
            raise RuntimeError("Zygisk 库不得依赖 APK 里的动态 C++ 运行库")
        artifacts.append((library, abi))
    # 两个 ABI 都验证成功后再更新待打包目录，防止混入旧库。
    destination = root / "MagiskModBase/zygisk"
    destination.mkdir(parents=True, exist_ok=True)
    for library, abi in artifacts:
        shutil.copy2(library, destination / f"{abi}.so")
        print(f"已生成 {abi}.so：{hashlib.sha256(library.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
