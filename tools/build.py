#!/usr/bin/env python3
"""构建两个 APK；本机签名私钥仅保存在已忽略的 .local 目录。"""
from pathlib import Path
import argparse
import base64
import hashlib
import os
import secrets
import shutil
import subprocess
import urllib.request
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
LOCAL = ROOT / '.local'
PLATFORM_SHA256 = 'c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8'


def run(args, **kwargs):
    return subprocess.run([str(x) for x in args], cwd=ROOT, check=True, **kwargs)


def prepare():
    LOCAL.mkdir(exist_ok=True)
    java = os.environ.get('JAVA_HOME')
    if not java and Path('/usr/libexec/java_home').exists():
        java = run(['/usr/libexec/java_home', '-v', '17'], capture_output=True, text=True).stdout.strip()
    if not java:
        raise RuntimeError('请设置 JAVA_HOME，使用 JDK 17 或更新版本')
    env = dict(os.environ, JAVA_HOME=java)
    env['PATH'] = str(Path(java) / 'bin') + os.pathsep + env.get('PATH', '')
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
    if not (sdk / 'platforms/android-35/android.jar').exists():
        raise RuntimeError('需要 Android SDK Platform 35 与 Build Tools 35.0.0')
    (ROOT / 'local.properties').write_text('sdk.dir=' + str(sdk) + '\n')
    if not (LOCAL / 'signing.properties').exists():
        password = secrets.token_hex(20)
        secret_env = dict(env, IREADER_SIGN_PASSWORD=password)
        keytool = Path(java) / 'bin/keytool'
        run([keytool, '-genkeypair', '-keystore', LOCAL / 'launcher.jks', '-storepass:env', 'IREADER_SIGN_PASSWORD',
             '-keypass:env', 'IREADER_SIGN_PASSWORD', '-alias', 'launcher', '-keyalg', 'RSA', '-keysize', '3072',
             '-validity', '36500', '-dname', 'CN=IReaderLauncher Local'], env=secret_env, capture_output=True)
        cert = run([keytool, '-exportcert', '-keystore', LOCAL / 'launcher.jks', '-storepass:env',
                    'IREADER_SIGN_PASSWORD', '-alias', 'launcher'], env=secret_env, capture_output=True).stdout
        (LOCAL / 'signing.properties').write_text('password=' + password + '\ncertificateSha256=' + hashlib.sha256(cert).hexdigest() + '\n')
        os.chmod(LOCAL / 'launcher.jks', 0o600)
        os.chmod(LOCAL / 'signing.properties', 0o600)
    # 仅用于匹配这台 userdebug 固件的公开 AOSP 开发证书，不能用作应用身份校验。
    for name in ['platform.x509.pem', 'platform.pk8']:
        path = LOCAL / name
        if not path.exists():
            url = 'https://android.googlesource.com/platform/build/+/refs/heads/android14-release/target/product/security/' + name + '?format=TEXT'
            path.write_bytes(base64.b64decode(urllib.request.urlopen(url, timeout=30).read()))
            os.chmod(path, 0o600)
    cert = run(['openssl', 'x509', '-in', LOCAL / 'platform.x509.pem', '-outform', 'DER'], capture_output=True).stdout
    if hashlib.sha256(cert).hexdigest() != PLATFORM_SHA256:
        raise RuntimeError('平台证书校验失败')
    return env, sdk


def sdk_build(env, sdk, checks):
    """使用官方 SDK 工具完成资源、AIDL、Java、DEX 与签名，无需 Maven 下载。"""
    output = ROOT / 'output'
    output.mkdir(exist_ok=True)
    bt = sdk / 'build-tools/35.0.0'
    platform = sdk / 'platforms/android-35'
    android_jar = platform / 'android.jar'
    signing = dict(line.split('=', 1) for line in (LOCAL / 'signing.properties').read_text().splitlines() if '=' in line)
    signing_env = dict(env, IREADER_SIGN_PASSWORD=signing['password'])
    ns = 'http://schemas.android.com/apk/res/android'
    ET.register_namespace('android', ns)

    def build(module, package, filename, shell=False, test=False, main_jar=None):
        build_dir = ROOT / module / 'build/sdk'
        build_dir.mkdir(parents=True, exist_ok=True)
        classes = build_dir / 'classes'
        # 删除上一次生成的 class，确保移除源码后不会残留旧功能。
        if classes.exists():
            shutil.rmtree(classes)
        classes.mkdir()
        generated = build_dir / 'generated'
        generated.mkdir(exist_ok=True)
        if test:
            manifest = ET.Element('manifest', {'package': package})
            ET.SubElement(manifest, 'application', {f'{{{ns}}}label': '阅桌面实机测试', f'{{{ns}}}testOnly': 'true'})
            ET.SubElement(manifest, 'instrumentation', {f'{{{ns}}}name': 'com.ireaderlauncher.app.SmokeInstrumentation', f'{{{ns}}}targetPackage': 'com.ireaderlauncher.app'})
            sources = list((ROOT / 'launcher/src/androidTest/java').rglob('*.java'))
        else:
            manifest = ET.parse(ROOT / module / 'src/main/AndroidManifest.xml').getroot()
            manifest.set('package', package)
            sources = list((ROOT / module / 'src/main/java').rglob('*.java'))
            aidl = ROOT / 'shared/aidl/com/ireaderlauncher/bridge/IPowerBridge.aidl'
            run([bt / 'aidl', '--lang=java', '-p' + str(platform / 'framework.aidl'), '-I' + str(ROOT / 'shared/aidl'), '-o' + str(generated), aidl], env=env)
            sources += list(generated.rglob('*.java'))
        manifest_file = build_dir / 'AndroidManifest.xml'
        ET.ElementTree(manifest).write(manifest_file, encoding='utf-8', xml_declaration=True)
        resources = ROOT / module / 'src/main/res'
        link = [bt / 'aapt2', 'link', '--manifest', manifest_file, '-I', android_jar, '--java', generated, '--min-sdk-version', '28',
                '--target-sdk-version', '34', '--version-code', '1', '--version-name', '0.1.0', '-o', build_dir / 'resources.apk']
        if resources.exists():
            run([bt / 'aapt2', 'compile', '--dir', resources, '-o', build_dir / 'compiled.zip'], env=env)
            link += [build_dir / 'compiled.zip']
        run(link, env=env)
        # 原生布局开始引用 R；SDK 构建也要编译 aapt2 生成的资源常量。
        for source in generated.rglob('R.java'):
            if source not in sources:
                sources.append(source)
        if shell:
            config = generated / 'com/ireaderlauncher/powerbridge/BuildConfig.java'
            config.parent.mkdir(parents=True, exist_ok=True)
            config.write_text('package com.ireaderlauncher.powerbridge; public final class BuildConfig { public static final String LAUNCHER_CERT_SHA256="' + signing['certificateSha256'] + '"; }\n')
            if config not in sources:
                sources.append(config)
        classpath = str(android_jar) + (os.pathsep + str(main_jar) if main_jar else '')
        run([Path(env['JAVA_HOME']) / 'bin/javac', '-encoding', 'UTF-8', '-source', '17', '-target', '17', '-classpath', classpath, '-d', classes] + sources, env=env)
        jar = build_dir / 'classes.jar'
        with zipfile.ZipFile(jar, 'w') as z:
            for path in classes.rglob('*.class'):
                z.write(path, path.relative_to(classes))
        dex = build_dir / 'dex'
        dex.mkdir(exist_ok=True)
        dex_cmd = [bt / 'd8', '--release', '--min-api', '28', '--lib', android_jar, '--output', dex]
        if main_jar:
            dex_cmd += ['--classpath', main_jar]
        run(dex_cmd + [jar], env=env)
        unsigned = build_dir / 'unsigned.apk'
        shutil.copy2(build_dir / 'resources.apk', unsigned)
        with zipfile.ZipFile(unsigned, 'a') as z:
            for path in dex.glob('*.dex'):
                z.write(path, path.name)
        aligned = build_dir / 'aligned.apk'
        run([bt / 'zipalign', '-f', '4', unsigned, aligned], env=env)
        sign = [bt / 'apksigner', 'sign', '--out', output / filename]
        if shell:
            sign += ['--key', LOCAL / 'platform.pk8', '--cert', LOCAL / 'platform.x509.pem']
        else:
            sign += ['--ks', LOCAL / 'launcher.jks', '--ks-key-alias', 'launcher', '--ks-pass', 'env:IREADER_SIGN_PASSWORD', '--key-pass', 'env:IREADER_SIGN_PASSWORD']
        run(sign + [aligned], env=signing_env)
        run([bt / 'apksigner', 'verify', output / filename], env=env)
        print('已构建：', filename, flush=True)
        return jar

    launcher_jar = build('launcher', 'com.ireaderlauncher.app', 'IReaderLauncher.apk')
    build('powerbridge', 'com.ireaderlauncher.powerbridge', 'IReaderPowerBridge.apk', shell=True)
    if checks:
        build('smoketest', 'com.ireaderlauncher.app.test', 'IReaderSmokeTest.apk', test=True, main_jar=launcher_jar)
        build('securityprobe', 'com.ireaderlauncher.probe', 'IReaderSecurityProbe.apk')
    print('APK 已生成：', output)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--check', action='store_true', help='构建实机测试 APK；Gradle 模式同时执行 lint')
    parser.add_argument('--gradle', action='store_true', help='使用 Gradle；默认直接使用 SDK 构建，无第三方依赖')
    args = parser.parse_args()
    env, sdk = prepare()
    if not args.gradle:
        sdk_build(env, sdk, args.check)
        return
    gradle = LOCAL / 'tools/gradle-8.9/bin/gradle'
    if not gradle.exists():
        distribution = LOCAL / 'tools/gradle-8.9-bin.zip'
        distribution.parent.mkdir(parents=True, exist_ok=True)
        if not zipfile.is_zipfile(distribution):
            urllib.request.urlretrieve('https://downloads.gradle.org/distributions/gradle-8.9-bin.zip', distribution)
        with zipfile.ZipFile(distribution) as z:
            z.extractall(distribution.parent)
        gradle.chmod(0o755)
    tasks = [':launcher:assembleRelease', ':powerbridge:assembleRelease']
    if args.check:
        tasks += [':launcher:lintRelease', ':powerbridge:lintRelease', ':launcher:assembleDebugAndroidTest']
    run([gradle, '--console=plain', '--no-daemon'] + (['--offline'] if args.offline else []) + tasks, env=env)
    output = ROOT / 'output'
    output.mkdir(exist_ok=True)
    shutil.copy2(ROOT / 'launcher/build/outputs/apk/release/launcher-release.apk', output / 'IReaderLauncher.apk')
    signer = sdk / 'build-tools/35.0.0/apksigner'
    run([signer, 'sign', '--key', LOCAL / 'platform.pk8', '--cert', LOCAL / 'platform.x509.pem',
         '--out', output / 'IReaderPowerBridge.apk', ROOT / 'powerbridge/build/outputs/apk/release/powerbridge-release-unsigned.apk'], env=env)
    for apk in output.glob('*.apk'):
        run([signer, 'verify', apk], env=env)
    print('APK 已生成：', output)


if __name__ == '__main__':
    main()
