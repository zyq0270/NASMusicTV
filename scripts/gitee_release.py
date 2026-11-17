#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Gitee Release 自动发版脚本。

在 Gitee 上打 tag 时：
  1. 打 tag v<version> 并推送到 gitee remote（若 tag 不存在）
  2. 从 CHANGELOG.md 提取本次版本（v<version>）的更新记录
  3. 调用 Gitee API 创建 Release（发行版），body = 更新记录
  4. 上传本地已编译好的 release APK 作为附件

用法（在仓库根目录运行）：
  # 需要 Gitee 私人令牌（access token），通过环境变量提供，不硬编码
  $env:GITEE_TOKEN = "你的私人令牌"
  python scripts/gitee_release.py                 # 自动取 app/build.gradle.kts 的 versionName
  python scripts/gitee_release.py 2.39.20         # 或显式指定版本
  python scripts/gitee_release.py --dry-run       # 只打印将执行的动作，不真正发布

依赖：Python 3 标准库（urllib / json / re / pathlib），无第三方依赖。

Gitee API 参考（gitee-release-cli 官方工具源码确认）：
  POST /repos/{owner}/{repo}/releases            -> 创建 release，body 含 access_token/tag_name/name/body
  POST /repos/{owner}/{repo}/releases/{id}/attach_files -> 上传附件，multipart 字段 file + access_token
"""

import argparse
import base64
import json
import os
import re
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

API_PREFIX = "https://gitee.com/api/v5/"
REPO_URL = "https://gitee.com/hxzhang2000/NASMusicTV.git"


def log(msg: str) -> None:
    print(msg, flush=True)


def fail(msg: str) -> None:
    print(f"[ERROR] {msg}", file=sys.stderr, flush=True)
    sys.exit(1)


def parse_version_from_gradle() -> str:
    """从 app/build.gradle.kts 读取 versionName，形如 \"2.39.20\"。"""
    gradle = Path("app/build.gradle.kts")
    if not gradle.exists():
        fail(f"找不到 {gradle}，请在仓库根目录运行。")
    text = gradle.read_text(encoding="utf-8")
    m = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not m:
        fail("未能从 app/build.gradle.kts 解析 versionName。")
    return m.group(1)


def extract_changelog_section(version: str) -> str:
    """从 CHANGELOG.md 提取 v<version> 小节（不含标题行）作为更新记录。

    返回从 '## [v<version>]' 行之后到下一个 '## [' 之前的全部内容（去除首尾空白）。
    """
    changelog = Path("CHANGELOG.md")
    if not changelog.exists():
        fail("找不到 CHANGELOG.md。")
    lines = changelog.read_text(encoding="utf-8").splitlines()
    # 容忍 CHANGELOG 里历史遗留的转义反斜杠：`## [vX]` 或 `## \[vX]`
    pattern = re.compile(r"^##\s*\\?\[v" + re.escape(version) + r"\]")
    section_end = re.compile(r"^##\s*\\?\[")
    start = None
    for i, line in enumerate(lines):
        if pattern.match(line):
            start = i + 1
            break
    if start is None:
        fail(f"CHANGELOG.md 中没有找到 '## [v{version}]' 条目。")
    body_lines = []
    for line in lines[start:]:
        if section_end.match(line):
            break
        body_lines.append(line)
    body = "\n".join(body_lines).strip()
    if not body:
        log(f"[WARN] CHANGELOG v{version} 小节为空，Release body 将使用占位文本。")
        body = f"Release {version}（详见仓库 CHANGELOG.md）"
    return body


def resolve_apk_path(version: str) -> Path:
    """定位本地已编译的 release APK。versionName 的 '.' 在 APK 文件名中替换为 '-'。"""
    dashed = version.replace(".", "-")
    candidates = [
        Path("app/build/outputs/apk/release") / f"NASMusicTV-release-v{dashed}.apk",
        Path("app/build/outputs/apk/release") / f"app-release.apk",
    ]
    for c in candidates:
        if c.exists():
            return c
    # 兜底：在 release 输出目录里找任意 .apk（排除含 debug 的）
    out_dir = Path("app/build/outputs/apk/release")
    if out_dir.exists():
        apks = sorted(out_dir.glob("*.apk"), key=lambda p: p.stat().st_mtime, reverse=True)
        if apks:
            return apks[0]
    fail(
        "未找到已编译的 release APK。请先构建：\n"
        "  $env:JAVA_HOME = 'C:\\Program Files\\Android\\Android Studio\\jbr'\n"
        "  ./gradlew.bat assembleRelease -Plofi.city=true --no-daemon -Pkotlin.compiler.execution.strategy=in-process"
    )


def gitee_api_request(method: str, path: str, data=None, multipart=None, token: str = "", raw: bool = False):
    """通用 Gitee API 请求。返回解析后的 JSON。"""
    url = API_PREFIX + path.lstrip("/")
    headers = {"Accept": "application/json"}
    if multipart is not None:
        # multipart/form-data：body 由调用方构造好，这里直接发
        headers["Content-Type"] = "multipart/form-data; boundary=" + multipart["boundary"]
        payload = multipart["body"]
    elif data is not None:
        headers["Content-Type"] = "application/json"
        payload = json.dumps(data).encode("utf-8")
    else:
        payload = None

    req = urllib.request.Request(url, data=payload, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            if raw:
                return body
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(detail)
            msg = parsed.get("message") or parsed.get("msg") or detail
        except Exception:
            msg = detail
        fail(f"Gitee API 请求失败 [{e.code}] {url}\n  {msg}")


def create_release(token: str, owner: str, repo: str, tag_name: str, name: str, body: str, target: str):
    """创建 Gitee Release，返回 release id。"""
    data = {
        "access_token": token,
        "tag_name": tag_name,
        "name": name,
        "body": body,
        "target_commitish": target,
        "prerelease": False,
    }
    result = gitee_api_request("POST", f"repos/{owner}/{repo}/releases", data=data, token=token)
    release_id = result.get("id")
    if not release_id:
        fail(f"创建 Release 成功但未拿到 id：{json.dumps(result, ensure_ascii=False)}")
    log(f"  Release 已创建：id={release_id} tag={result.get('tag_name', tag_name)}")
    return release_id


def upload_asset(token: str, owner: str, repo: str, release_id: int, apk_path: Path):
    """上传本地 APK 到指定 Release。multipart 字段：file + access_token。"""
    boundary = "----GiteeRelease" + uuid.uuid4().hex
    parts = []
    parts.append(
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"access_token\"\r\n\r\n{token}\r\n".encode("utf-8")
    )
    file_name = apk_path.name
    file_data = apk_path.read_bytes()
    parts.append(
        (
            f"--{boundary}\r\n"
            f"Content-Disposition: form-data; name=\"file\"; filename=\"{file_name}\"\r\n"
            "Content-Type: application/octet-stream\r\n\r\n"
        ).encode("utf-8")
    )
    parts.append(file_data)
    parts.append(f"\r\n--{boundary}--\r\n".encode("utf-8"))
    body = b"".join(parts)
    multipart = {"boundary": boundary, "body": body}
    result = gitee_api_request(
        "POST",
        f"repos/{owner}/{repo}/releases/{release_id}/attach_files",
        multipart=multipart,
        token=token,
    )
    log(f"  附件上传完成：{file_name} ({apk_path.stat().st_size / 1024 / 1024:.1f} MB)")
    return result


def main():
    parser = argparse.ArgumentParser(description="Gitee 自动发版：打 tag + 创建 Release + 上传本地 APK")
    parser.add_argument("version", nargs="?", help="版本号，如 2.39.20；缺省从 app/build.gradle.kts 读取")
    parser.add_argument("--owner", default="hxzhang2000", help="Gitee 用户名（默认 hxzhang2000）")
    parser.add_argument("--repo", default="NASMusicTV", help="Gitee 仓库名（默认 NASMusicTV）")
    parser.add_argument("--target", default="main", help="tag 指向的分支（默认 main）")
    parser.add_argument("--no-push-tag", action="store_true", help="不推送 tag（仅创建 Release + 上传 APK）")
    parser.add_argument("--dry-run", action="store_true", help="只打印将执行的动作，不真正发布")
    args = parser.parse_args()

    version = args.version or parse_version_from_gradle()
    if not re.match(r"^\d+\.\d+\.\d+$", version):
        fail(f"版本号格式无效：{version}（应为 x.y.z）")
    tag = f"v{version}"
    apk = resolve_apk_path(version)
    body = extract_changelog_section(version)

    log(f"版本：{version}  tag：{tag}")
    log(f"APK：{apk} ({apk.stat().st_size / 1024 / 1024:.1f} MB)")
    log(f"更新记录（{len(body)} 字符）：")
    for line in body.splitlines()[:12]:
        log(f"    {line}")
    if len(body.splitlines()) > 12:
        log(f"    ...（共 {len(body.splitlines())} 行）")

    if args.dry_run:
        log("\n[DRY-RUN] 将执行：")
        log(f"  1. git tag {tag} && git push gitee {args.target} --tags")
        log(f"  2. POST /repos/{args.owner}/{args.repo}/releases (tag={tag}, name=... , body=<更新记录>)")
        log(f"  3. POST /repos/{args.owner}/{args.repo}/releases/<id>/attach_files (file={apk.name})")
        log("  （DRY-RUN 不检查 GITEE_TOKEN，不实际发布）")
        return

    # Token 读取顺序：gitee.properties（本地忽略配置）→ 环境变量 GITEE_TOKEN
    token = ""
    props = Path("gitee.properties")
    if props.exists():
        for line in props.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                if k.strip() == "gitee.token":
                    token = v.strip()
    if not token:
        token = os.environ.get("GITEE_TOKEN", "").strip()
    if not token:
        fail(
            "缺少 Gitee 私人令牌。请把令牌填入 gitee.properties 的 gitee.token=，"
            "或设置环境变量 GITEE_TOKEN（Gitee → 设置 → 私人令牌 获取）。"
        )

    # 1. 打 tag 并推送到 gitee（若本地 tag 不存在）
    if not args.no_push_tag:
        existing = os.popen(f"git tag -l {tag}").read().strip()
        if existing:
            log(f"  tag {tag} 已存在，跳过创建。")
        else:
            log(f"  打 tag：{tag}")
            if os.system(f"git tag {tag}") != 0:
                fail("git tag 失败。")
        log(f"  推送到 gitee：{tag}")
        if os.system(f"git push gitee {args.target} --tags") != 0:
            fail("git push gitee 失败（tag）。")

    # 2. 创建 Release（更新记录作为 body）
    release_name = f"{tag}"
    log(f"  创建 Gitee Release：{release_name}")
    release_id = create_release(token, args.owner, args.repo, tag, release_name, body, args.target)

    # 3. 上传本地 APK
    log(f"  上传 APK 到 Release #{release_id}")
    upload_asset(token, args.owner, args.repo, release_id, apk)

    log(f"\n✅ 完成。可在 https://gitee.com/{args.owner}/{args.repo}/releases 查看。")


if __name__ == "__main__":
    main()
