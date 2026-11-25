import contextlib
import hashlib
import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock


REPO_ROOT = Path(__file__).resolve().parents[2]
RELEASE_SCRIPT = REPO_ROOT / "scripts" / "gitee_release.py"
MIRROR_SCRIPT = REPO_ROOT / "scripts" / "sync_gitee_mirror.ps1"


def load_release_script():
    spec = importlib.util.spec_from_file_location("gitee_release", RELEASE_SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def powershell_command():
    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        for name in ("pwsh.exe", "pwsh"):
            candidate = Path(local_app_data) / "Microsoft" / "WindowsApps" / name
            if candidate.is_file():
                return str(candidate)
    return shutil.which("pwsh") or shutil.which("powershell")


class GiteeReleaseApkSelectionTest(unittest.TestCase):
    def setUp(self):
        self.module = load_release_script()
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.out = self.root / "app" / "build" / "outputs" / "apk" / "release"
        self.out.mkdir(parents=True)
        self.old_cwd = Path.cwd()
        os.chdir(self.root)

    def tearDown(self):
        os.chdir(self.old_cwd)
        self.tmp.cleanup()

    def test_missing_exact_apk_never_falls_back_to_app_release(self):
        (self.out / "app-release.apk").write_bytes(b"wrong artifact")

        with self.assertRaises(SystemExit):
            self.module.resolve_apk_path("2.39.27")

    def test_missing_exact_apk_never_falls_back_to_latest_apk(self):
        (self.out / "NASMusicTV-release-v9-99-99.apk").write_bytes(b"wrong artifact")

        with self.assertRaises(SystemExit):
            self.module.resolve_apk_path("2.39.27")


class GiteeMirrorPowerShellSafetyTest(unittest.TestCase):
    def setUp(self):
        self.shell = powershell_command()
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        self.script = scripts / MIRROR_SCRIPT.name
        shutil.copy2(MIRROR_SCRIPT, self.script)

    def tearDown(self):
        self.tmp.cleanup()

    def run_mirror(
        self, *, branch="main", detached=False, dirty=False, action="PublishLocal", legacy=False,
        foreign_cwd=False, script_branch=None, branch_after_checks=None,
        remote_main="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa refs/heads/main",
        tag_refs="", tag_object="dddddddddddddddddddddddddddddddddddddddd",
        tag_commit="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    ):
        if not self.shell:
            self.skipTest("PowerShell 不可用")
        foreign = self.root / "foreign-main-worktree"
        foreign.mkdir(exist_ok=True)
        command = r"""
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$OutputEncoding = [Console]::OutputEncoding
$calls = New-Object 'System.Collections.Generic.List[string]'
$script:symbolicRefCalls = 0
function git {
    $all = @($args)
    $calls.Add(($all -join ' '))
    $global:LASTEXITCODE = 0
    $i = 0
    while ($i -lt $all.Count -and $all[$i] -eq '-c') { $i += 2 }
    $cmd = if ($i -lt $all.Count) { $all[$i] } else { '' }
    switch ($cmd) {
        'rev-parse' {
            if ($all -contains '--is-inside-work-tree') { return 'true' }
            if ($all -contains 'refs/heads/main') { return 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' }
            if ($all[-1] -like '*^{commit}') { return $env:FAKE_TAG_COMMIT }
            if ($all[-1] -like 'refs/tags/*') { return $env:FAKE_TAG_OBJECT }
            return 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
        }
        'symbolic-ref' {
            $script:symbolicRefCalls += 1
            if ($env:FAKE_DETACHED -eq '1') { $global:LASTEXITCODE = 1; return }
            if ((Get-Location).Path -eq $env:FAKE_FOREIGN_CWD) { return 'main' }
            if ($script:symbolicRefCalls -ge 2 -and $env:FAKE_BRANCH_AFTER_CHECKS) { return $env:FAKE_BRANCH_AFTER_CHECKS }
            return $env:FAKE_SCRIPT_BRANCH
        }
        'status' {
            if ($env:FAKE_DIRTY -eq '1') { return @(' M scripts/gitee_release.py', '?? private.apk') }
            return
        }
        'remote' { return @('origin') }
        'push' { return }
        'tag' {
            if ($all -contains '--merged') {
                return @($env:FAKE_TAG_REFS -split "`n" | Where-Object { $_ })
            }
            return
        }
        'ls-remote' { return $env:FAKE_REMOTE_MAIN }
        'fetch' { return }
        'merge-base' { return }
        default { throw "unexpected git command: $($all -join ' ')" }
    }
}
function Invoke-RestMethod { throw "unexpected network call: Invoke-RestMethod" }
function Invoke-WebRequest { throw "unexpected network call: Invoke-WebRequest" }
$params = @{
    Action = $env:FAKE_ACTION
    Token = 'dummy-token'
}
if ($env:FAKE_LEGACY -eq '1') { $params.AllowLegacyGitHubSync = $true }
try {
    & $env:FAKE_SCRIPT @params
    $code = 0
} catch {
    Write-Output ("CAUGHT:" + $_.Exception.Message)
    $code = 23
}
$calls | ForEach-Object { Write-Output ("CALL:" + $_) }
exit $code
"""
        env = os.environ.copy()
        env.update(
            FAKE_SCRIPT=str(self.script),
            FAKE_ACTION=action,
            FAKE_BRANCH=branch,
            FAKE_SCRIPT_BRANCH=script_branch if script_branch is not None else branch,
            FAKE_BRANCH_AFTER_CHECKS=branch_after_checks or "",
            FAKE_REMOTE_MAIN=remote_main,
            FAKE_TAG_REFS=tag_refs,
            FAKE_TAG_OBJECT=tag_object,
            FAKE_TAG_COMMIT=tag_commit,
            FAKE_FOREIGN_CWD=str(foreign),
            FAKE_DETACHED="1" if detached else "0",
            FAKE_DIRTY="1" if dirty else "0",
            FAKE_LEGACY="1" if legacy else "0",
        )
        return subprocess.run(
            [self.shell, "-NoProfile", "-NonInteractive", "-Command", command],
            cwd=foreign if foreign_cwd else self.root,
            env=env,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            timeout=30,
        )

    def assert_read_only_failure(self, result, expected):
        output = result.stdout + result.stderr
        self.assertEqual(23, result.returncode, output)
        self.assertIn(expected, output)
        calls = [line[5:] for line in output.splitlines() if line.startswith("CALL:")]
        self.assertTrue(calls, output)
        for call in calls:
            self.assertRegex(call, r"^(?:-c\s+\S+\s+)*(rev-parse|symbolic-ref|status)(?:\s|$)", call)

    def test_private_branch_fails_before_any_git_write_or_network(self):
        result = self.run_mirror(branch="feat/e51-private")
        self.assert_read_only_failure(result, "main")

    def test_detached_head_fails_before_any_git_write_or_network(self):
        result = self.run_mirror(detached=True)
        self.assert_read_only_failure(result, "detached")

    def test_dirty_worktree_fails_before_any_git_write_or_network(self):
        result = self.run_mirror(dirty=True)
        self.assert_read_only_failure(result, "干净")

    def test_legacy_push_cannot_bypass_main_preflight(self):
        result = self.run_mirror(branch="feat/e51-private", action="Push", legacy=True)
        self.assert_read_only_failure(result, "main")

    def test_foreign_clean_main_cannot_bypass_target_repo_branch_guard(self):
        result = self.run_mirror(script_branch="feat/e51-private", foreign_cwd=True)
        self.assert_read_only_failure(result, "main")

    def test_publish_rechecks_main_immediately_before_push(self):
        result = self.run_mirror(branch_after_checks="feat/e51-private")
        self.assert_read_only_failure(result, "main")

    def test_publish_rejects_remote_main_mismatch_after_push(self):
        result = self.run_mirror(remote_main="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb refs/heads/main")
        output = result.stdout + result.stderr
        self.assertIn("回读校验失败", output)
        self.assertNotIn("发布完成", output)

    def test_publish_pushes_tags_by_locked_object_sha(self):
        result = self.run_mirror(
            tag_refs="refs/tags/v2.39.27",
            tag_object="dddddddddddddddddddddddddddddddddddddddd",
        )
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        tag_pushes = [
            line[5:] for line in output.splitlines()
            if line.startswith("CALL:") and " push " in line and "refs/tags/" in line
        ]
        self.assertEqual(1, len(tag_pushes), output)
        self.assertIn("dddddddddddddddddddddddddddddddddddddddd:refs/tags/v2.39.27", tag_pushes[0])
        self.assertNotIn("refs/tags/v2.39.27:refs/tags/v2.39.27", tag_pushes[0])

    def test_publish_allows_reachable_historical_tag(self):
        result = self.run_mirror(
            tag_refs="refs/tags/v2.39.20",
            tag_object="dddddddddddddddddddddddddddddddddddddddd",
            tag_commit="cccccccccccccccccccccccccccccccccccccccc",
        )
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn(
            "dddddddddddddddddddddddddddddddddddddddd:refs/tags/v2.39.20",
            output,
        )

    def test_legacy_push_pushes_tags_by_locked_object_sha(self):
        result = self.run_mirror(
            action="Push",
            legacy=True,
            tag_refs="refs/tags/v2.39.20",
            tag_object="dddddddddddddddddddddddddddddddddddddddd",
            tag_commit="cccccccccccccccccccccccccccccccccccccccc",
        )
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        tag_pushes = [
            line[5:] for line in output.splitlines()
            if line.startswith("CALL:") and " push " in line and "refs/tags/" in line
        ]
        self.assertEqual(1, len(tag_pushes), output)
        self.assertIn("dddddddddddddddddddddddddddddddddddddddd:refs/tags/v2.39.20", tag_pushes[0])
        self.assertNotIn("refs/tags/v2.39.20:refs/tags/v2.39.20", tag_pushes[0])

    def test_legacy_push_rejects_remote_main_mismatch_after_push(self):
        result = self.run_mirror(
            action="Push",
            legacy=True,
            remote_main="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb refs/heads/main",
        )
        output = result.stdout + result.stderr
        self.assertIn("REMOTE_MAIN_MISMATCH", output)
        self.assertNotIn("PUSH_COMPLETE", output)

    def test_publish_uses_explicit_main_refspec_not_head(self):
        result = self.run_mirror()
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        push_calls = [line[5:] for line in output.splitlines() if line.startswith("CALL:") and " push " in line]
        self.assertEqual(1, len(push_calls), output)
        self.assertIn("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:refs/heads/main", push_calls[0])
        self.assertNotIn("HEAD:refs/heads/main", push_calls[0])
        self.assertNotIn("refs/heads/main:refs/heads/main", push_calls[0])


class GiteeReleasePreflightTest(unittest.TestCase):
    VERSION = "2.39.27"
    VERSION_CODE = 212
    APK_NAME = "NASMusicTV-release-v2-39-27.apk"

    def setUp(self):
        self.module = load_release_script()
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.out = self.root / "app" / "build" / "outputs" / "apk" / "release"
        self.out.mkdir(parents=True)
        self.mapping = self.root / "app" / "build" / "outputs" / "mapping" / "release" / "mapping.txt"
        self.mapping.parent.mkdir(parents=True)
        self.mapping.write_text("com.nasmusic.tv.MainActivity -> a:\n", encoding="utf-8")
        self.apk = self.out / self.APK_NAME
        self.write_apk({"classes.dex": b"dex\n"})
        self.metadata = self.out / "output-metadata.json"
        self.write_metadata()
        (self.root / "app" / "build.gradle.kts").write_text(
            'android {\n  defaultConfig {\n    versionCode = 212\n    versionName = "2.39.27"\n  }\n}\n',
            encoding="utf-8",
        )
        (self.root / "CHANGELOG.md").write_text(
            "## [v2.39.27]\n- 安全修复\n\n## [v2.39.26]\n- 旧版本\n",
            encoding="utf-8",
        )
        self.old_cwd = Path.cwd()
        os.chdir(self.root)

    def tearDown(self):
        os.chdir(self.old_cwd)
        self.tmp.cleanup()

    def write_apk(self, entries):
        with zipfile.ZipFile(self.apk, "w", zipfile.ZIP_DEFLATED) as archive:
            for name, data in entries.items():
                archive.writestr(name, data)

    def write_metadata(self, *, version_name=None, output_file=None, version_code=None):
        value = {
            "version": 3,
            "artifactType": {"type": "APK", "kind": "Directory"},
            "applicationId": "com.nasmusic.tv",
            "variantName": "release",
            "elements": [
                {
                    "type": "SINGLE",
                    "filters": [],
                    "attributes": [],
                    "versionCode": self.VERSION_CODE if version_code is None else version_code,
                    "versionName": self.VERSION if version_name is None else version_name,
                    "outputFile": self.APK_NAME if output_file is None else output_file,
                }
            ],
            "elementType": "File",
        }
        self.metadata.write_text(json.dumps(value), encoding="utf-8")

    def write_proof(self, *, sha256=None, lofi=False, highway=False, overlay=None):
        proof = {
            "schema": 1,
            "type": "NASMusicTV-public-release-proof",
            "apk": {
                "file": self.APK_NAME,
                "bytes": self.apk.stat().st_size,
                "sha256": sha256 or hashlib.sha256(self.apk.read_bytes()).hexdigest(),
            },
            "outputMetadata": {
                "file": "output-metadata.json",
                "sha256": hashlib.sha256(self.metadata.read_bytes()).hexdigest(),
            },
            "build": {
                "applicationId": "com.nasmusic.tv",
                "variantName": "release",
                "versionCode": self.VERSION_CODE,
                "versionName": self.VERSION,
                "target": "main",
                "lofi.city": lofi,
                "highway.enabled": highway,
                "overlay": overlay,
            },
            "source": {"branch": "main", "commit": "a" * 40},
        }
        self.proof_path().write_text(json.dumps(proof), encoding="utf-8")

    def proof_path(self):
        return self.out / f"{self.APK_NAME}.public-proof.json"

    def fake_git(
        self, *, branch="main", detached=False, dirty=False, calls=None, allow_writes=False,
        tag_exists=False, head="a" * 40, head_after_checks=None, remote_tag=None, tag_object=None,
    ):
        head_reads = 0

        def run(args, **kwargs):
            nonlocal head_reads
            if calls is not None:
                calls.append(list(args))
            command = args[1] if args and args[0] == "git" else (args[0] if args else "")
            stdout = ""
            code = 0
            if command == "symbolic-ref":
                if detached:
                    code = 1
                else:
                    stdout = branch
            elif command == "rev-parse":
                if "HEAD" in args:
                    head_reads += 1
                    stdout = head_after_checks if head_after_checks and head_reads >= 2 else head
                elif "refs/heads/main" in args:
                    stdout = head_after_checks if head_after_checks and head_reads >= 2 else head
                elif args[-1] == f"refs/tags/v{self.VERSION}^{{commit}}":
                    stdout = head
                elif args[-1] == f"refs/tags/v{self.VERSION}":
                    stdout = tag_object or head
                else:
                    stdout = head
            elif command == "status":
                stdout = " M dirty.kt\n" if dirty else ""
            elif command == "tag":
                if "--list" in args:
                    stdout = f"v{self.VERSION}" if tag_exists else ""
                elif allow_writes:
                    stdout = ""
                else:
                    raise AssertionError(f"unexpected git write during preflight: {args}")
            elif command == "ls-remote":
                if remote_tag is None:
                    code = 2
                else:
                    stdout = remote_tag
            elif command == "push" and allow_writes:
                stdout = ""
            else:
                raise AssertionError(f"unexpected git command during preflight: {args}")
            return subprocess.CompletedProcess(args, code, stdout, "")
        return run

    def run_release(self, *args, git_runner=None):
        argv = ["gitee_release.py", *args]
        stdout = io.StringIO()
        with mock.patch.object(sys, "argv", argv), mock.patch.dict(os.environ, {"GITEE_TOKEN": "dummy-token"}), contextlib.redirect_stdout(stdout):
            if git_runner is None:
                self.module.main()
            else:
                with mock.patch("subprocess.run", side_effect=git_runner):
                    self.module.main()
        return stdout.getvalue()

    def assert_preflight_failure(self, expected, *args, git_runner=None):
        stderr = io.StringIO()
        with mock.patch.object(self.module, "gitee_api_request") as api, contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as caught:
                self.run_release(*args, git_runner=git_runner)
        api.assert_not_called()
        self.assertEqual(1, caught.exception.code)
        self.assertIn(expected, stderr.getvalue())

    def assert_only_read_git(self, calls):
        for args in calls:
            command_index = 1 if args and args[0] == "git" else 0
            while command_index < len(args) and args[command_index] == "-c":
                command_index += 2
            self.assertLess(command_index, len(args), args)
            if args[command_index] == "tag" and "--list" in args:
                continue
            self.assertIn(args[command_index], {"symbolic-ref", "rev-parse", "status", "ls-remote"}, args)

    def test_wrong_target_is_rejected_before_git_or_network(self):
        calls = []
        self.assert_preflight_failure(
            "target", "--target", "feat/e51-private", "--dry-run", git_runner=self.fake_git(calls=calls)
        )
        self.assertEqual([], calls)

    def test_private_branch_is_rejected_before_git_writes_or_network(self):
        calls = []
        self.assert_preflight_failure(
            "main", "--dry-run", git_runner=self.fake_git(branch="feat/e51-private", calls=calls)
        )
        self.assertTrue(calls)
        self.assert_only_read_git(calls)

    def test_detached_head_is_rejected_before_git_writes_or_network(self):
        self.assert_preflight_failure("detached", "--dry-run", git_runner=self.fake_git(detached=True))

    def test_dirty_worktree_is_rejected_before_git_writes_or_network(self):
        self.assert_preflight_failure("干净", "--dry-run", git_runner=self.fake_git(dirty=True))

    def test_no_push_tag_still_runs_all_preflight(self):
        calls = []
        with mock.patch.object(self.module, "create_release") as create, mock.patch.object(self.module, "upload_asset") as upload:
            self.assert_preflight_failure(
                "main", "--no-push-tag", git_runner=self.fake_git(branch="feat/e51-private", calls=calls)
            )
        create.assert_not_called()
        upload.assert_not_called()

    def test_metadata_version_mismatch_is_rejected(self):
        self.write_metadata(version_name="2.39.26")
        self.assert_preflight_failure("versionName", "--dry-run", git_runner=self.fake_git())

    def test_metadata_output_file_mismatch_is_rejected(self):
        self.write_metadata(output_file="app-release.apk")
        self.assert_preflight_failure("outputFile", "--dry-run", git_runner=self.fake_git())

    def test_metadata_version_code_mismatch_is_rejected(self):
        self.write_metadata(version_code=211)
        self.assert_preflight_failure("versionCode", "--dry-run", git_runner=self.fake_git())

    def test_e52_assets_are_rejected_before_git_writes_or_network(self):
        self.write_apk({"classes.dex": b"dex\n", "assets/e52_highway/manifest.json": b"{}"})
        self.assert_preflight_failure("e52_highway", "--dry-run", git_runner=self.fake_git())

    def test_e51_only_dex_is_rejected_even_without_e52_assets(self):
        self.write_apk({"classes.dex": b"Lcom/nasmusic/tv/visualizer/e51/engine/CityRegistry;"})
        self.assert_preflight_failure("E51", "--dry-run", git_runner=self.fake_git())

    def test_private_marker_in_mapping_is_rejected(self):
        self.mapping.write_text("com.nasmusic.tv.visualizer.highway.HighwayRenderer -> a:\n", encoding="utf-8")
        self.assert_preflight_failure("E52", "--dry-run", git_runner=self.fake_git())

    def test_existing_proof_with_wrong_apk_hash_is_rejected(self):
        self.write_proof(sha256="0" * 64)
        self.assert_preflight_failure("SHA256", "--dry-run", git_runner=self.fake_git())

    def test_existing_proof_with_private_flags_is_rejected(self):
        for private_fields in (
            {"lofi": True},
            {"highway": True},
            {"overlay": "app/src/e51-overlay"},
        ):
            with self.subTest(private_fields=private_fields):
                self.write_proof(**private_fields)
                self.assert_preflight_failure("公开证明", "--dry-run", git_runner=self.fake_git())

    def test_valid_dry_run_writes_nothing_and_only_reads_git(self):
        calls = []
        output = self.run_release("--dry-run", git_runner=self.fake_git(calls=calls))
        self.assertFalse(self.proof_path().exists())
        self.assert_only_read_git(calls)
        self.assertIn("lofi.city=false, highway.enabled=false, overlay=null", output)

    def test_formal_release_generates_hash_bound_proof_and_pushes_only_exact_refs(self):
        calls = []
        created = []
        uploaded = []

        def create(*args):
            self.assertTrue(self.proof_path().is_file(), "API 调用前必须已生成公开证明")
            created.append(args)
            return 7

        def upload(*args):
            uploaded.append(args)
            return {}

        with mock.patch.object(self.module, "create_release", side_effect=create), mock.patch.object(
            self.module, "upload_asset", side_effect=upload
        ):
            self.run_release(git_runner=self.fake_git(calls=calls, allow_writes=True))

        proof = json.loads(self.proof_path().read_text(encoding="utf-8"))
        self.assertEqual(hashlib.sha256(self.apk.read_bytes()).hexdigest(), proof["apk"]["sha256"])
        self.assertEqual(self.APK_NAME, proof["apk"]["file"])
        self.assertEqual(self.apk.stat().st_size, proof["apk"]["bytes"])
        self.assertEqual("2.39.27", proof["build"]["versionName"])
        self.assertEqual(212, proof["build"]["versionCode"])
        self.assertEqual("main", proof["build"]["target"])
        self.assertIs(False, proof["build"]["lofi.city"])
        self.assertIs(False, proof["build"]["highway.enabled"])
        self.assertIsNone(proof["build"]["overlay"])
        self.assertEqual("a" * 40, proof["source"]["commit"])
        self.assertEqual(["git", "tag", "v2.39.27", "a" * 40], next(call for call in calls if call[1] == "tag" and "--list" not in call))
        push = next(call for call in calls if call[1] == "push")
        self.assertEqual(["git", "push", "gitee", "a" * 40 + ":refs/heads/main", "a" * 40 + ":refs/tags/v2.39.27"], push)
        self.assertNotIn("--tags", push)
        self.assertEqual("a" * 40, created[0][-1])
        self.assertEqual(self.APK_NAME, uploaded[0][-2])
        self.assertEqual(self.apk.read_bytes(), uploaded[0][-1])
        self.assertEqual(self.proof_path().name, uploaded[1][-2])
        self.assertEqual(self.proof_path().read_bytes(), uploaded[1][-1])

    def test_existing_annotated_tag_is_pushed_by_locked_object_sha(self):
        calls = []
        with mock.patch.object(self.module, "create_release", return_value=7), mock.patch.object(
            self.module, "upload_asset", return_value={}
        ):
            self.run_release(
                git_runner=self.fake_git(
                    calls=calls,
                    allow_writes=True,
                    tag_exists=True,
                    tag_object="d" * 40,
                )
            )
        push = next(call for call in calls if call[1] == "push")
        self.assertEqual(
            ["git", "push", "gitee", "a" * 40 + ":refs/heads/main", "d" * 40 + ":refs/tags/v2.39.27"],
            push,
        )

    def test_no_push_tag_valid_release_has_no_git_writes(self):
        calls = []
        with mock.patch.object(self.module, "create_release", return_value=7) as create, mock.patch.object(
            self.module, "upload_asset", return_value={}
        ) as upload:
            self.run_release("--no-push-tag", git_runner=self.fake_git(calls=calls))
        self.assert_only_read_git(calls)
        self.assertTrue(self.proof_path().is_file())
        create.assert_called_once()
        self.assertEqual(2, upload.call_count)

    def test_no_push_tag_rejects_remote_tag_at_other_commit(self):
        calls = []
        with mock.patch.object(self.module, "create_release") as create, mock.patch.object(
            self.module, "upload_asset"
        ) as upload:
            self.assert_preflight_failure(
                "远端 tag",
                "--no-push-tag",
                git_runner=self.fake_git(
                    calls=calls,
                    remote_tag="b" * 40 + "\trefs/tags/v2.39.27\n",
                ),
            )
        self.assert_only_read_git(calls)
        self.assertFalse(self.proof_path().exists())
        create.assert_not_called()
        upload.assert_not_called()

    def test_no_push_tag_accepts_annotated_remote_tag_peeled_to_head(self):
        calls = []
        with mock.patch.object(self.module, "create_release", return_value=7) as create, mock.patch.object(
            self.module, "upload_asset", return_value={}
        ):
            self.run_release(
                "--no-push-tag",
                git_runner=self.fake_git(
                    calls=calls,
                    remote_tag=(
                        "c" * 40 + "\trefs/tags/v2.39.27\n" +
                        "a" * 40 + "\trefs/tags/v2.39.27^{}\n"
                    ),
                ),
            )
        self.assert_only_read_git(calls)
        self.assertTrue(self.proof_path().is_file())
        create.assert_called_once()

    def test_head_change_before_side_effects_aborts_without_proof_or_api(self):
        calls = []
        with mock.patch.object(self.module, "create_release") as create, mock.patch.object(
            self.module, "upload_asset"
        ) as upload:
            self.assert_preflight_failure(
                "HEAD",
                "--no-push-tag",
                git_runner=self.fake_git(calls=calls, head_after_checks="b" * 40),
            )
        self.assert_only_read_git(calls)
        self.assertFalse(self.proof_path().exists())
        create.assert_not_called()
        upload.assert_not_called()

    def test_upload_uses_preflight_bytes_even_if_apk_changes_before_api(self):
        original = self.apk.read_bytes()

        def create(*args):
            self.apk.write_bytes(b"replaced after preflight")
            return 7

        with mock.patch.object(self.module, "create_release", side_effect=create), mock.patch.object(
            self.module, "upload_asset", return_value={}
        ) as upload:
            self.run_release("--no-push-tag", git_runner=self.fake_git())
        self.assertEqual(original, upload.call_args_list[0].args[-1])
        proof = json.loads(self.proof_path().read_text(encoding="utf-8"))
        self.assertEqual(hashlib.sha256(original).hexdigest(), proof["apk"]["sha256"])


if __name__ == "__main__":
    unittest.main()
