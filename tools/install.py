#!/usr/bin/env python3
"""校验目标固件后安装，保存默认桌面原值，并支持恢复桌面。"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess

ROOT = Path(__file__).resolve().parent.parent
EXPECTED_CERT = 'c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8'
HOME = 'com.ireaderlauncher.app/.MainActivity'


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

    backup = ROOT / '.local' / ('device-' + serial + '.json')
    if args.restore_home:
        original = json.loads(backup.read_text())['home'] if backup.exists() else 'com.zhangyue.iReader.Eink/.home.ActivityLauncher'
        print(shell('cmd', 'package', 'set-home-activity', '--user', '0', original))
        print(shell('am', 'start', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'))
        return
    if shell('getprop', 'ro.product.model') != 'Neo 3 Ultra' or shell('getprop', 'ro.build.version.sdk') != '34':
        raise RuntimeError('当前版本只适配已分析的 Neo 3 Ultra / Android 14 固件')
    # 从实际 Shell APK 的证书核验签名匹配，避免对其他固件盲目安装。
    cert_blob = subprocess.check_output(adb + ['exec-out', 'unzip', '-p', '/system/priv-app/Shell/Shell.apk', 'META-INF/CERT.RSA'])
    pem = subprocess.run(['openssl', 'pkcs7', '-inform', 'DER', '-print_certs'], input=cert_blob, capture_output=True, check=True).stdout
    cert = subprocess.run(['openssl', 'x509', '-outform', 'DER'], input=pem, capture_output=True, check=True).stdout
    if hashlib.sha256(cert).hexdigest() != EXPECTED_CERT:
        raise RuntimeError('系统签名与权限组件不匹配，停止安装')
    if not backup.exists():
        result = shell('cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
        home = next((line for line in reversed(result.splitlines()) if '/' in line and ' ' not in line), None)
        if not home:
            raise RuntimeError('无法读取当前默认桌面，停止安装')
        backup.parent.mkdir(exist_ok=True)
        backup.write_text(json.dumps({'home': home}, ensure_ascii=False, indent=2))
    for name in ['IReaderLauncher.apk', 'IReaderPowerBridge.apk']:
        subprocess.run(adb + ['install', '-r', '--no-streaming', str(ROOT / 'output' / name)], check=True)
    print(shell('cmd', 'package', 'set-home-activity', '--user', '0', HOME))
    print(shell('am', 'start', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'))
    print('默认桌面：', shell('cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'))


if __name__ == '__main__':
    main()
