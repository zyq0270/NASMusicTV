"""MIUI 手机上的 ADB 安装**自动确认**器。

## 为什么需要它

MIUI 对 ADB 安装会拉起安全中心的确认界面
`com.miui.securitycenter/com.miui.permcenter.install.AdbInstallActivity`。
**手机锁屏时该界面被立即销毁**（logcat: `Input channel object '… AdbInstallActivity (client)'
was disposed without first being removed`）⇒ 系统判为「用户取消」⇒
`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`。

本脚本在安装**等待期间**用 uiautomator 抓界面，找到「继续安装」按钮并自动点击 ⇒
**不需要人工点**（但**手机必须已解锁**，锁屏时弹窗根本不会被画出来）。

已实测无效的绕行（不要再试）：`verifier_verify_adb_installs` / `adb_install_need_confirm` /
`install_via_usb` / `appops REQUEST_INSTALL_PACKAGES` / `pm disable-user com.miui.securitycenter`
（系统包，抛 `SecurityException`）/ `locksettings set-disabled true`（MIUI 会重置回 false）/
`adb root`（量产机被拒）。详见 `docs/technical-overview.md` §7.16.6。

## 用法

```bash
python scripts/e50_install_tap.py path/to/app.apk
python scripts/e50_install_tap.py path/to/app.apk --pkg com.nasmusic.tv.debug
```

⚠️ **装之前先确认手机已解锁**（`adb shell dumpsys window | grep mDreamingLockscreen` 应为 `false`）。
⚠️ **签名不一致时不要用它去装「同包名」的包**：UTP/系统的替换安装会**先卸载旧包** ⇒
**App 数据（服务器连接与凭据）会丢** ⇒ 改用不同的包名（见 `app/build.gradle.kts` 的
`-Pe50.debugSuffix=true` 开关）。
"""
import os
import re
import subprocess
import sys
import time

ADB = os.environ.get('ADB', r"C:\Users\hxzha\AppData\Local\Android\Sdk\platform-tools\adb.exe")
SERIAL = os.environ.get('ANDROID_SERIAL', '91846823')
TMP = os.path.join(os.environ.get('TEMP', '/tmp'), 'opencode', 'phone_scr')
XML = os.path.join(TMP, 'ui_adb.xml')

# MIUI 确认界面上的肯定按钮（按优先级）
CONFIRM_WORDS = ["继续安装", "安装", "允许", "确定", "确认", "继续", "好的", "好", "OK", "Install"]


def adb(*args, timeout=120):
    return subprocess.run([ADB, '-s', SERIAL] + list(args),
                          capture_output=True, text=True, timeout=timeout)


def dump_ui():
    adb('shell', 'uiautomator', 'dump', '/sdcard/ui_adb.xml')
    adb('pull', '/sdcard/ui_adb.xml', XML)
    adb('shell', 'rm', '/sdcard/ui_adb.xml')
    if not os.path.isfile(XML):
        return None
    try:
        with open(XML, encoding='utf-8', errors='replace') as fh:
            return fh.read()
    except OSError:
        return None


def nodes(xml):
    out = []
    for m in re.findall(r'<node[^>]*>', xml):
        t = re.search(r'text="([^"]*)"', m)
        d = re.search(r'content-desc="([^"]*)"', m)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', m)
        rid = re.search(r'resource-id="([^"]*)"', m)
        c = re.search(r'clickable="(\w+)"', m)
        if not b:
            continue
        label = (t.group(1) if t and t.group(1) else '') or (d.group(1) if d and d.group(1) else '')
        x1, y1, x2, y2 = map(int, b.groups())
        out.append({
            'label': label,
            'rid': rid.group(1) if rid else '',
            'clickable': c.group(1) if c else '?',
            'x': (x1 + x2) // 2,
            'y': (y1 + y2) // 2,
        })
    return out


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    apk = sys.argv[1]
    pkg_kw = 'securitycenter'
    if '--pkg' in sys.argv:
        pkg_kw = sys.argv[sys.argv.index('--pkg') + 1]
    os.makedirs(TMP, exist_ok=True)

    # 先看一眼锁屏状态（锁屏 ⇒ 弹窗不会被画出来 ⇒ 必然失败）
    st = adb('shell', 'dumpsys', 'window').stdout or ''
    m = re.search(r'mDreamingLockscreen=(\w+)', st)
    if m and m.group(1) == 'true':
        print('!! 设备处于锁屏状态 ⇒ MIUI 的安装确认界面不会被画出来 ⇒ 必然 USER_RESTRICTED')
        print('!! 请先解锁手机，再重跑本脚本。')

    print('=== 后台发起安装 ===', flush=True)
    proc = subprocess.Popen([ADB, '-s', SERIAL, 'install', '-r', '-t', apk],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

    tapped = False
    for i in range(30):
        time.sleep(0.8)
        xml = dump_ui()
        if not xml or pkg_kw not in xml:
            continue
        print('  [第 %d 次] 抓到 MIUI 安装确认界面' % i, flush=True)
        ns = nodes(xml)
        for n in ns:
            if n['label'] or n['clickable'] == 'true':
                print('    node %-30s rid=%-42s click=%-5s @ %d,%d'
                      % (n['label'][:30], n['rid'][-42:], n['clickable'], n['x'], n['y']))
        for kw in CONFIRM_WORDS:
            hit = None
            for n in ns:
                if kw in n['label'] and (n['clickable'] == 'true' or n['rid']):
                    hit = n
                    break
            if hit:
                print("    >> 点击 '%s' @ %d,%d" % (hit['label'], hit['x'], hit['y']), flush=True)
                adb('shell', 'input', 'tap', str(hit['x']), str(hit['y']))
                tapped = True
                break
        if tapped:
            break

    out = proc.stdout.read() if proc.stdout else ''
    proc.wait()
    print('')
    print('=== 安装结果 ===')
    print('  ' + out.strip().replace('\r\n', ' | '))
    print('  tapped=%s' % tapped)
    print('')
    print('=== 设备包状态 ===')
    r = adb('shell', 'pm', 'list', 'packages')
    for line in (r.stdout or '').splitlines():
        if 'nasmusic' in line:
            print('  ' + line.strip())
    return 0


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    sys.exit(main())
