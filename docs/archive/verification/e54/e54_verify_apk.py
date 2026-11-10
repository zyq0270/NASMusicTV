# -*- coding: utf-8 -*-
"""E54 第二轮 release 包离线校验（不依赖 Gradle / Android SDK）。

用法：python logs_temp/e54_verify_apk.py <apk路径>
核对：指纹 / 上游图集 / 无散装 PNG / 版本字符串 / 双语标签 / dex 引用。
"""
import hashlib
import os
import sys
import zipfile


def u16(s):
    return s.encode("utf-16-le")


def main():
    apk = sys.argv[1]
    raw = open(apk, "rb").read()
    print("APK           :", apk)
    print("bytes         :", len(raw))
    print("sha256        :", hashlib.sha256(raw).hexdigest())
    print("sha256(12)    :", hashlib.sha256(raw).hexdigest()[:12])
    print("mtime         :", __import__("datetime").datetime.fromtimestamp(os.path.getmtime(apk)))

    ok = True
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()

        atlas = [n for n in names if n.startswith("assets/e54_arcade/")]
        print("\n[e54_arcade assets]")
        for n in atlas:
            print("  ", n, z.getinfo(n).file_size, "B")
        exact = "assets/e54_arcade/contra_atlas.png" in names
        size_ok = exact and z.getinfo("assets/e54_arcade/contra_atlas.png").file_size == 55839
        print("  仅一张图集 :", atlas == ["assets/e54_arcade/contra_atlas.png"])
        print("  图集大小   :", size_ok)
        ok &= atlas == ["assets/e54_arcade/contra_atlas.png"] and size_ok

        loose = [n for n in names if n.lower().endswith(".png") and "sprite_" in n.lower()]
        print("  散装精灵PNG:", len(loose), "(应为 0)")
        ok &= not loose

        print("\n[版本 / 标签]")
        manifest = z.read("AndroidManifest.xml")
        # versionName 在 AXML 字符串池里（UTF-8 或 UTF-16）；versionCode 是二进制 int，
        # 字符串搜不到 ⇒ 由外部 `aapt2 dump badging` 单独核对，这里只打印不参与判定。
        hit = "2.39.17".encode() in manifest or u16("2.39.17") in manifest
        print("  %-20s: %s" % ("versionName 2.39.17", hit))
        ok &= hit
        print("  %-20s: %s" % ("versionCode 202", "见 aapt2 dump badging"))
        arsc = z.read("resources.arsc")
        for label, needle in (("中文标签 街机合集", "街机合集"), ("英文标签 Arcade", "Arcade")):
            hit = needle.encode("utf-8") in arsc or u16(needle) in arsc
            print("  %-20s: %s" % (label, hit))
            ok &= hit

        print("\n[dex 引用]")
        dex = b"".join(z.read(n) for n in names if n.startswith("classes") and n.endswith(".dex"))
        for label, needle in (("图集 asset 路径", "e54_arcade/contra_atlas.png"),
                              ("上游帧表类", "ContraSpriteFrames"),
                              ("语义槽映射", "ContraArt")):
            hit = needle.encode() in dex
            print("  %-20s: %s" % (label, hit))
            ok &= hit

    print("\nRESULT        :", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
