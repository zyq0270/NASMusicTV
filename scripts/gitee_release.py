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
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path

API_PREFIX = "https://gitee.com/api/v5/"
REPO_URL = "https://gitee.com/hxzhang2000/NASMusicTV.git"
EXPECTED_APPLICATION_ID = "com.nasmusic.tv"
PROOF_SCHEMA = 1
PROOF_TYPE = "NASMusicTV-public-release-proof"

# 只扫描私有实现的特有类名/路径，不误伤公开仓库中保留的 LOFI_CITY / HIGHWAY 枚举与开关接缝。
PRIVATE_MARKERS = (
    ("E51", b"lcom/nasmusic/tv/visualizer/e51/"),
    ("E51", b"com.nasmusic.tv.visualizer.e51."),
    ("E51", b"lcom/nasmusic/tv/visualizer/pixel/"),
    ("E51", b"com.nasmusic.tv.visualizer.pixel."),
    ("E51", b"lcom/nasmusic/tv/visualizer/scenes/lofi/"),
    ("E51", b"com.nasmusic.tv.visualizer.scenes.lofi."),
    ("E51", b"loficityrenderer"),
    ("E51", b"loficitycard"),
    ("E51", b"loficities.com"),
    ("E52", b"lcom/nasmusic/tv/visualizer/highway/"),
    ("E52", b"com.nasmusic.tv.visualizer.highway."),
    ("E52", b"highwayrenderer"),
    ("E52", b"e52_highway"),
)


def log(msg: str) -> None:
    # GBK 控制台（Windows 中文默认）无法编码 emoji/部分字符，强制 stdout 用 UTF-8
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    print(msg, flush=True)


def fail(msg: str) -> None:
    try:
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    print(f"[ERROR] {msg}", file=sys.stderr, flush=True)
    sys.exit(1)


def parse_version_from_gradle(root: Path = Path(".")) -> str:
    """从 app/build.gradle.kts 读取 versionName，形如 \"2.39.20\"。"""
    gradle = Path(root) / "app/build.gradle.kts"
    if not gradle.is_file():
        fail(f"找不到 {gradle}，请在仓库根目录运行。")
    text = gradle.read_text(encoding="utf-8")
    m = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', text, re.MULTILINE)
    if not m:
        fail("未能从 app/build.gradle.kts 解析 versionName。")
    return m.group(1)


def parse_version_code_from_gradle(root: Path = Path(".")) -> int:
    gradle = Path(root) / "app/build.gradle.kts"
    if not gradle.is_file():
        fail(f"找不到 {gradle}，请在仓库根目录运行。")
    m = re.search(r"^\s*versionCode\s*=\s*(\d+)\s*$", gradle.read_text(encoding="utf-8"), re.MULTILINE)
    if not m:
        fail("未能从 app/build.gradle.kts 解析 versionCode。")
    return int(m.group(1))


def git_result(args: list[str], root: Path = Path(".")) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(
            ["git", *args], cwd=Path(root), capture_output=True, text=True,
            encoding="utf-8", errors="replace", check=False,
        )
    except OSError as exc:
        fail(f"无法执行 git：{exc}")


def run_git(args: list[str], root: Path = Path(".")) -> str:
    """执行 git 并返回 stdout；调用方负责保证只在 preflight 完成后才传入写操作。"""
    result = git_result(args, root)
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        fail(f"git {' '.join(args)} 失败：{detail}")
    return result.stdout.strip()


def validate_repository(target: str, root: Path = Path(".")) -> str:
    """发布只接受干净、非 detached 的当前 main；返回精确 HEAD SHA。"""
    if target != "main":
        fail(f"target 只允许精确的 'main'，收到 '{target}'；禁止发布私有分支或任意 ref。")
    branch_result = git_result(["symbolic-ref", "--quiet", "--short", "HEAD"], root)
    if branch_result.returncode != 0:
        fail("发布安全护栏：当前处于 detached HEAD，禁止打 tag / 推送 / 上传；请先切回 main。")
    branch = branch_result.stdout.strip()
    if branch != "main":
        fail(f"发布安全护栏：只允许当前 main，当前分支为 '{branch}'；私有分支永不发布。")
    head = run_git(["rev-parse", "--verify", "HEAD"], root)
    main_sha = run_git(["rev-parse", "--verify", "refs/heads/main"], root)
    if not re.fullmatch(r"[0-9a-fA-F]{40,64}", head) or head.lower() != main_sha.lower():
        fail("发布安全护栏：HEAD 与 refs/heads/main 不一致，禁止打 tag / 推送 / 上传。")
    dirty = run_git(["status", "--porcelain=v1", "--untracked-files=all"], root)
    if dirty:
        fail(f"发布安全护栏：工作树不干净，禁止打 tag / 推送 / 上传。请先提交或还原改动：\n{dirty}")
    return head.lower()


def validate_existing_tag(tag: str, head: str, root: Path = Path(".")) -> bool:
    """本地若已有同名 tag，必须精确指向当前 main 的 HEAD。"""
    listed = run_git(["tag", "--list", tag], root)
    if not listed:
        return False
    if listed.splitlines() != [tag]:
        fail(f"本地 tag 列表异常：{listed}")
    tagged = run_git(["rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}"], root).lower()
    if tagged != head:
        fail(f"tag {tag} 指向 {tagged}，不是当前 main 的 {head}；禁止创建版本错配的 Release。")
    return True


def locked_tag_object(tag: str, head: str, root: Path = Path(".")) -> str:
    """返回 tag 对象本身的 SHA，并再次确认它 peeled 后仍指向锁定 HEAD。"""
    ref = f"refs/tags/{tag}"
    tag_object = run_git(["rev-parse", "--verify", ref], root).lower()
    tagged = run_git(["rev-parse", "--verify", f"{ref}^{{commit}}"], root).lower()
    if tagged != head.lower():
        fail(f"tag {tag} 在推送前变为指向 {tagged}，不再是锁定的 {head}；禁止继续发布。")
    return tag_object


def validate_remote_tag(tag: str, head: str, root: Path = Path(".")) -> bool:
    """远端若已有同名 tag，必须精确指向当前 main 的 HEAD；不存在时允许后续创建。"""
    ref = f"refs/tags/{tag}"
    result = git_result(["ls-remote", "--exit-code", "gitee", ref, f"{ref}^{{}}"], root)
    if result.returncode == 2:
        return False
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        fail(f"无法校验 Gitee 远端 tag {tag}：{detail}")
    direct = None
    peeled = None
    for line in result.stdout.splitlines():
        fields = line.split()
        if len(fields) != 2:
            fail(f"Gitee 远端 tag {tag} 的 ls-remote 输出异常：{line}")
        sha, name = fields
        if name == ref:
            direct = sha.lower()
        elif name == f"{ref}^{{}}":
            peeled = sha.lower()
    actual = peeled or direct
    if actual is None:
        fail(f"Gitee 远端 tag {tag} 的 ls-remote 输出缺少 {ref}。")
    if actual != head.lower():
        fail(f"Gitee 远端 tag {tag} 指向 {actual}，不是当前 main 的 {head}；禁止创建版本错配的 Release。")
    return True


def extract_changelog_section(version: str, root: Path = Path(".")) -> str:
    """从 CHANGELOG.md 提取 v<version> 小节（不含标题行）作为更新记录。

    返回从 '## [v<version>]' 行之后到下一个 '## [' 之前的全部内容（去除首尾空白）。
    """
    changelog = Path(root) / "CHANGELOG.md"
    if not changelog.is_file():
        fail(f"找不到 {changelog}。")
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


def resolve_apk_path(version: str, root: Path = Path(".")) -> Path:
    """只定位与版本精确匹配的 release APK；绝不回退到其它文件名或最新产物。"""
    dashed = version.replace(".", "-")
    apk = Path(root) / "app/build/outputs/apk/release" / f"NASMusicTV-release-v{dashed}.apk"
    if not apk.is_file():
        fail(
            f"未找到精确版本的 release APK：{apk}\n"
            "禁止回退到 app-release.apk 或目录中的最新任意 APK。请先构建公开版本：\n"
            "  ./gradlew.bat assembleRelease -Plofi.city=false -Phighway.enabled=false "
            "--no-daemon -Pkotlin.compiler.execution.strategy=in-process\n"
            "  同时确认 local.properties 未将 lofi.city/highway.enabled 设为 true（其优先级高于 -P）。"
        )
    return apk


def validate_output_metadata(apk: Path, version: str, version_code: int) -> str:
    """校验 AGP 元数据精确指向本版本、release 变体和当前 APK 文件名。"""
    metadata_path = apk.parent / "output-metadata.json"
    if not metadata_path.is_file():
        fail(f"缺少 {metadata_path}；无法证明 APK 的版本与变体。")
    try:
        metadata_bytes = metadata_path.read_bytes()
        metadata = json.loads(metadata_bytes.decode("utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        fail(f"output-metadata.json 无法读取或解析：{exc}")
    if not isinstance(metadata, dict):
        fail("output-metadata.json 顶层必须是 JSON 对象。")
    if metadata.get("version") != 3 or metadata.get("elementType") != "File":
        fail("output-metadata.json 必须是 AGP version=3、elementType=File。")
    artifact_type = metadata.get("artifactType")
    if not isinstance(artifact_type, dict) or artifact_type.get("type") != "APK":
        fail("output-metadata.json artifactType.type 必须是 APK。")
    if metadata.get("applicationId") != EXPECTED_APPLICATION_ID:
        fail(f"output-metadata.json applicationId 必须是 {EXPECTED_APPLICATION_ID}。")
    if metadata.get("variantName") != "release":
        fail("output-metadata.json variantName 必须是 release。")
    elements = metadata.get("elements")
    if not isinstance(elements, list) or len(elements) != 1 or not isinstance(elements[0], dict):
        fail("output-metadata.json 必须恰好包含一个 APK element。")
    element = elements[0]
    if element.get("type") != "SINGLE" or element.get("filters") != [] or element.get("attributes") != []:
        fail("output-metadata.json 必须是单一、无 ABI 过滤的 release APK。")
    if element.get("versionName") != version:
        fail(f"output-metadata.json versionName={element.get('versionName')!r}，与请求版本 {version} 不符。")
    actual_code = element.get("versionCode")
    if type(actual_code) is not int or actual_code != version_code:
        fail(f"output-metadata.json versionCode={actual_code!r}，与 Gradle versionCode={version_code} 不符。")
    if element.get("outputFile") != apk.name:
        fail(f"output-metadata.json outputFile={element.get('outputFile')!r}，与精确 APK {apk.name} 不符。")
    return hashlib.sha256(metadata_bytes).hexdigest()


def scan_public_apk(apk_name: str, apk_data: bytes, mapping_path: Path) -> None:
    """拒绝 E52 资产，并扫描所有 DEX 与 R8 mapping 中的 E51/E52 私有标记。"""
    if not mapping_path.is_file() or mapping_path.stat().st_size == 0:
        fail(f"缺少或为空 {mapping_path}；公开 release 必须提供 mapping 供私有标记扫描。")
    try:
        with zipfile.ZipFile(io.BytesIO(apk_data)) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)):
                fail("APK 含重复 ZIP 条目，禁止上传。")
            bad = archive.testzip()
            if bad:
                fail(f"APK ZIP 条目损坏：{bad}")
            e52_assets = [name for name in names if name.casefold().startswith("assets/e52_highway/")]
            if e52_assets:
                fail(f"APK 含 E52 私有资产 assets/e52_highway/：{e52_assets[0]}")
            dex_names = [name for name in names if re.fullmatch(r"classes[0-9]*\.dex", name)]
            if not dex_names:
                fail("APK 中找不到 classes*.dex，无法完成私有标记扫描。")
            scan_targets = [(name, archive.read(name)) for name in dex_names]
    except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
        fail(f"APK 无法解析：{exc}")
    try:
        scan_targets.append((str(mapping_path), mapping_path.read_bytes()))
    except OSError as exc:
        fail(f"mapping 无法读取：{exc}")
    for source, data in scan_targets:
        lowered = data.lower()
        for label, marker in PRIVATE_MARKERS:
            if marker in lowered:
                fail(f"{source} 含 {label} 私有标记 {marker.decode('ascii')}；禁止公开上传。")


def public_proof_path(apk: Path) -> Path:
    return apk.with_name(apk.name + ".public-proof.json")


def build_public_proof(
    apk: Path, apk_data: bytes, metadata_sha256: str, version: str, version_code: int, head: str,
) -> dict:
    return {
        "schema": PROOF_SCHEMA,
        "type": PROOF_TYPE,
        "apk": {
            "file": apk.name,
            "bytes": len(apk_data),
            "sha256": hashlib.sha256(apk_data).hexdigest(),
        },
        "outputMetadata": {"file": "output-metadata.json", "sha256": metadata_sha256},
        "build": {
            "applicationId": EXPECTED_APPLICATION_ID,
            "variantName": "release",
            "versionCode": version_code,
            "versionName": version,
            "target": "main",
            "lofi.city": False,
            "highway.enabled": False,
            "overlay": None,
        },
        "source": {"branch": "main", "commit": head},
    }


def proof_bytes(proof: dict) -> bytes:
    return (json.dumps(proof, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def validate_public_proof(path: Path, expected: dict) -> bytes:
    try:
        actual = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        fail(f"公开证明 {path} 无法读取或解析：{exc}")
    if not isinstance(actual, dict) or not isinstance(actual.get("apk"), dict):
        fail(f"公开证明 {path} 结构无效。")
    if actual["apk"].get("sha256") != expected["apk"]["sha256"]:
        fail(f"公开证明 APK SHA256 不符：证明记录 {actual['apk'].get('sha256')}，实际 {expected['apk']['sha256']}。")
    if json.dumps(actual, sort_keys=True, ensure_ascii=False) != json.dumps(expected, sort_keys=True, ensure_ascii=False):
        fail("公开证明内容不符：版本、target、Git 提交、私有开关或 overlay 绑定已变化，禁止上传。")
    return proof_bytes(expected)


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


def upload_asset(token: str, owner: str, repo: str, release_id: int, file_name: str, file_data: bytes):
    """上传 preflight 时锁定的字节，避免校验后文件被替换造成 TOCTOU 绕过。"""
    boundary = "----GiteeRelease" + uuid.uuid4().hex
    parts = [
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"access_token\"\r\n\r\n{token}\r\n".encode("utf-8"),
        (
            f"--{boundary}\r\n"
            f"Content-Disposition: form-data; name=\"file\"; filename=\"{file_name}\"\r\n"
            "Content-Type: application/octet-stream\r\n\r\n"
        ).encode("utf-8"),
        file_data,
        f"\r\n--{boundary}--\r\n".encode("utf-8"),
    ]
    result = gitee_api_request(
        "POST",
        f"repos/{owner}/{repo}/releases/{release_id}/attach_files",
        multipart={"boundary": boundary, "body": b"".join(parts)},
        token=token,
    )
    log(f"  附件上传完成：{file_name} ({len(file_data) / 1024 / 1024:.1f} MB)")
    return result


def load_token(root: Path) -> str:
    token = ""
    props = root / "gitee.properties"
    if props.is_file():
        for line in props.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                if key.strip() == "gitee.token":
                    token = value.strip()
    return token or os.environ.get("GITEE_TOKEN", "").strip()


def main():
    parser = argparse.ArgumentParser(description="Gitee 自动发版：校验公开包 + 打 tag + 创建 Release + 上传 APK/证明")
    parser.add_argument("version", nargs="?", help="版本号，如 2.39.20；缺省从 app/build.gradle.kts 读取")
    parser.add_argument("--owner", default="hxzhang2000", help="Gitee 用户名（默认 hxzhang2000）")
    parser.add_argument("--repo", default="NASMusicTV", help="Gitee 仓库名（默认 NASMusicTV）")
    parser.add_argument("--target", default="main", help="只允许 main")
    parser.add_argument("--no-push-tag", action="store_true", help="不创建/推送 tag；仍执行全部发布前校验")
    parser.add_argument("--dry-run", action="store_true", help="执行全部只读 preflight，不写证明、不真正发布")
    args = parser.parse_args()
    root = Path(".")

    gradle_version = parse_version_from_gradle(root)
    version = args.version or gradle_version
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        fail(f"版本号格式无效：{version}（应为 x.y.z）")
    if version != gradle_version:
        fail(f"请求版本 {version} 与 app/build.gradle.kts 的 versionName={gradle_version} 不一致。")
    version_code = parse_version_code_from_gradle(root)
    tag = f"v{version}"

    # 以下全部是只读 preflight；任何失败都发生在 git tag/push、Gitee API、upload 之前。
    head = validate_repository(args.target, root)
    tag_exists = validate_existing_tag(tag, head, root)
    apk = resolve_apk_path(version, root)
    metadata_sha256 = validate_output_metadata(apk, version, version_code)
    try:
        apk_data = apk.read_bytes()  # 后续上传只使用这份已验证字节，不重新读盘。
    except OSError as exc:
        fail(f"APK 无法读取：{exc}")
    mapping_path = root / "app/build/outputs/mapping/release/mapping.txt"
    scan_public_apk(apk.name, apk_data, mapping_path)
    proof = build_public_proof(apk, apk_data, metadata_sha256, version, version_code, head)
    proof_path = public_proof_path(apk)
    proof_data = proof_bytes(proof)
    proof_existed = proof_path.exists()
    if proof_existed:
        proof_data = validate_public_proof(proof_path, proof)
    body = extract_changelog_section(version, root)

    log(f"版本：{version}  tag：{tag}  target：main@{head}")
    log(f"APK：{apk} ({len(apk_data) / 1024 / 1024:.1f} MB)")
    log(f"APK SHA256：{proof['apk']['sha256']}")
    log("公开构建：lofi.city=false, highway.enabled=false, overlay=null；DEX/mapping/E52 资产扫描通过。")
    log(f"公开证明：{proof_path}" + ("（已校验）" if proof_existed else "（将在正式发布前生成）"))
    log(f"更新记录（{len(body)} 字符）：")
    for line in body.splitlines()[:12]:
        log(f"    {line}")
    if len(body.splitlines()) > 12:
        log(f"    ...（共 {len(body.splitlines())} 行）")

    if args.dry_run:
        log("\n[DRY-RUN] 全部 preflight 已通过；不会写证明、打 tag、push 或访问网络：")
        if args.no_push_tag:
            log("  1. 跳过 git tag / push")
        else:
            log(f"  1. git tag {tag} {head}（若不存在）")
            log(f"  2. git push gitee {head}:refs/heads/main refs/tags/{tag}:refs/tags/{tag}")
        log(f"  3. POST /repos/{args.owner}/{args.repo}/releases (tag={tag}, target={head})")
        log(f"  4. 上传 {apk.name} 与 {proof_path.name}")
        return

    # dry-run 保持纯本地只读；正式发布在产生任何副作用前再次锁定 HEAD/main 与远端 tag。
    current_head = validate_repository(args.target, root)
    if current_head != head:
        fail(f"发布安全护栏：HEAD 已从 {head} 变为 {current_head}；APK 与公开证明未绑定新提交，禁止继续。")
    tag_exists = validate_existing_tag(tag, head, root)
    remote_tag_exists = validate_remote_tag(tag, head, root)
    log(f"  Gitee 远端 tag {tag}：" + (f"已存在并指向 {head}" if remote_tag_exists else "不存在"))

    # preflight 全部完成后才生成证明、读取令牌并产生副作用。
    if not proof_existed:
        try:
            proof_path.write_bytes(proof_data)
        except OSError as exc:
            fail(f"无法生成公开证明 {proof_path}：{exc}")
        log(f"  已生成公开证明：{proof_path}")
    token = load_token(root)
    if not token:
        fail(
            "缺少 Gitee 私人令牌。请把令牌填入 gitee.properties 的 gitee.token=，"
            "或设置环境变量 GITEE_TOKEN（Gitee → 设置 → 私人令牌 获取）。"
        )

    if not args.no_push_tag:
        if not tag_exists:
            log(f"  打 tag：{tag} -> {head}")
            run_git(["tag", tag, head], root)
        tag_object = locked_tag_object(tag, head, root)
        log(f"  推送精确 main {head} 与 tag 对象 {tag_object} 到 gitee")
        run_git(["push", "gitee", f"{head}:refs/heads/main", f"{tag_object}:refs/tags/{tag}"], root)
    else:
        # 不推 tag 时，Release API 可能直接绑定远端已有 tag；API 前再校验一次，杜绝错版绑定。
        validate_remote_tag(tag, head, root)

    body += (
        "\n\n---\n公开构建证明\n"
        f"- APK SHA256: `{proof['apk']['sha256']}`\n"
        f"- target: `main@{head}`\n"
        "- lofi.city=false, highway.enabled=false, overlay=null\n"
        "- DEX/mapping 私有标记扫描与 E52 资产扫描：通过"
    )
    release_name = tag
    log(f"  创建 Gitee Release：{release_name}")
    release_id = create_release(token, args.owner, args.repo, tag, release_name, body, head)

    log(f"  上传 APK 到 Release #{release_id}")
    upload_asset(token, args.owner, args.repo, release_id, apk.name, apk_data)
    log(f"  上传公开证明到 Release #{release_id}")
    upload_asset(token, args.owner, args.repo, release_id, proof_path.name, proof_data)

    log(f"\n完成。可在 https://gitee.com/{args.owner}/{args.repo}/releases 查看。")


if __name__ == "__main__":
    main()
