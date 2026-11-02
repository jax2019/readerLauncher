#!/usr/bin/env python3
"""校验目标固件后安装，保存默认桌面原值，并支持恢复桌面。"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parent.parent
EXPECTED_CERT = 'c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8'
HOME = 'com.ireaderlauncher.app/.MainActivity'


def device_profile(model, sdk):
    if (model, sdk) == ('Neo 3 Ultra', '34'):
        return 'ireader'
    if (model, sdk) == ('MiDuoKanReaderProII', '30'):
        return 'duokan'
    raise RuntimeError('当前仅适配 Neo 3 Ultra / Android 14 与多看 Pro II / Android 11')


def backup_path(local, serial):
    # ADB 序列号可能包含斜杠，不能直接作为文件名；完整摘要避免设备之间冲突。
    return local / ('device-' + hashlib.sha256(serial.encode('utf-8')).hexdigest() + '.json')


def read_backup(local, serial):
    path = backup_path(local, serial)
    if not path.exists() and re.fullmatch(r'[A-Za-z0-9_.-]+', serial):
        path = local / ('device-' + serial + '.json')
    if not path.exists():
        return None
    record = json.loads(path.read_text())
    if record.get('serial', serial) != serial:
        raise RuntimeError('桌面备份与当前设备不匹配，停止操作')
    home = record.get('home', '')
    if not re.fullmatch(r'[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+', home) or home.startswith('com.ireaderlauncher.app/'):
        raise RuntimeError('备份中没有有效的原默认桌面，停止操作')
    return record


def resolved_component(output):
    return next((line.strip() for line in reversed(output.splitlines())
                 if re.fullmatch(r'[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+', line.strip())), None)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial')
    parser.add_argument('--restore-home', action='store_true')
    args = parser.parse_args()
    devices = subprocess.check_output(['adb', 'devices'], text=True).splitlines()[1:]
    serials = [line.split()[0] for line in devices if line.strip().endswith('\tdevice')]
    serial = args.serial or (serials[0] if len(serials) == 1 else None)
    if not serial:
        raise RuntimeError('请连接一台设备，或用 --serial 指定目标')
    adb = ['adb', '-s', serial]

    def shell(*parts):
        return subprocess.check_output(adb + ['shell'] + list(parts), text=True).strip()

    local = ROOT / '.local'
    backup = backup_path(local, serial)
    record = read_backup(local, serial)
    model = shell('getprop', 'ro.product.model')
    sdk = shell('getprop', 'ro.build.version.sdk')

    def select_home(component):
        # Android 11 多看仅设置 preferred activity 会在重启时被旧 HOME 角色覆盖。
        # 先同步角色，再指定具体 Activity，安装和恢复共用同一路径。
        if int(sdk) >= 29:
            shell('cmd', 'role', 'add-role-holder', '--user', '0', 'android.app.role.HOME', component.split('/')[0])
        print(shell('cmd', 'package', 'set-home-activity', '--user', '0', component))
        actual = resolved_component(shell('cmd', 'package', 'resolve-activity', '--brief',
                                          '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'))
        def expanded(value):
            if not value:
                return None
            package, activity = value.split('/', 1)
            return package, package + activity if activity.startswith('.') else activity
        if expanded(actual) != expanded(component):
            raise RuntimeError('默认桌面未生效，请检查系统的默认应用设置')
        print(shell('am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'))

    if record and record.get('model', model) != model:
        raise RuntimeError('桌面备份记录的机型与设备不匹配，停止操作')
    if args.restore_home:
        if not record:
            raise RuntimeError('未找到此设备的原默认桌面备份，不会猜测其他厂商桌面')
        original = record['home']
        if not resolved_component(shell('cmd', 'package', 'resolve-activity', '--brief', '-n', original)):
            raise RuntimeError('原默认桌面不可用，请先在系统设置中重新启用对应应用')
        select_home(original)
        return
    profile = device_profile(model, sdk)
    apks = ['IReaderLauncher.apk']
    if profile == 'ireader':
        # 仅掌阅安装特权组件，并维持原有的实际系统签名校验。
        cert_blob = subprocess.check_output(adb + ['exec-out', 'unzip', '-p', '/system/priv-app/Shell/Shell.apk', 'META-INF/CERT.RSA'])
        pem = subprocess.run(['openssl', 'pkcs7', '-inform', 'DER', '-print_certs'], input=cert_blob, capture_output=True, check=True).stdout
        cert = subprocess.run(['openssl', 'x509', '-outform', 'DER'], input=pem, capture_output=True, check=True).stdout
        if hashlib.sha256(cert).hexdigest() != EXPECTED_CERT:
            raise RuntimeError('系统签名与权限组件不匹配，停止安装')
        apks.append('IReaderPowerBridge.apk')
    for name in apks:
        if not (ROOT / 'output' / name).is_file():
            raise RuntimeError('缺少安装包，请先构建：' + name)
    if not record:
        result = shell('cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
        home = resolved_component(result)
        if not home or home.startswith('com.ireaderlauncher.app/'):
            raise RuntimeError('无法确定原默认桌面，请先在系统设置中选择要备份的默认桌面')
        record = {'home': home}
    if not backup.exists():
        # 兼容旧掌阅备份：迁移文件名，但不覆盖首次保存的 HOME。
        record.update(serial=serial, model=model, sdk=sdk, profile=profile,
                      fingerprint=shell('getprop', 'ro.build.fingerprint'))
        local.mkdir(parents=True, exist_ok=True)
        backup.write_text(json.dumps(record, ensure_ascii=False, indent=2))
    print('设备适配：', profile, '；安装：', ', '.join(apks))
    for name in apks:
        subprocess.run(adb + ['install', '-r', '--no-streaming', str(ROOT / 'output' / name)], check=True)
    if profile == 'duokan':
        # 普通 UID 的有限设置授权在重启后保留；锁屏设备管理授权仍由用户在系统界面确认。
        shell('pm', 'grant', '--user', '0', 'com.ireaderlauncher.app', 'android.permission.WRITE_SECURE_SETTINGS')
        shell('cmd', 'appops', 'set', '--user', '0', 'com.ireaderlauncher.app', 'WRITE_SETTINGS', 'allow')
        print('已授予多看系统省电及自动息屏设置权限；锁屏首次使用时单独确认。')
    select_home(HOME)
    print('默认桌面：', shell('cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'))


if __name__ == '__main__':
    main()
