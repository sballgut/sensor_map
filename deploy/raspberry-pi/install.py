"""First install only: sudo python3 install.py. Existing installations are never overwritten."""
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parent
SERVER = ROOT.parent.parent / 'server'
APP = Path('/opt/sensorae-server')
DATA = Path('/var/lib/sensorae-server')
ENV = Path('/etc/sensorae-server.env')
UNIT = Path('/etc/systemd/system/sensorae-server.service')
RUNTIME_FILES = ('api.py', 'scan_windows.py', 'admin.html')

def run(*args):
    subprocess.run(args, check=True)

def main():
    if sys.platform != 'linux' or not hasattr(os, 'geteuid'):
        raise RuntimeError('Run this installer on the Raspberry Pi, not Windows.')
    if os.geteuid() != 0:
        raise RuntimeError('Run: sudo python3 install.py')
    if sys.version_info < (3, 10):
        raise RuntimeError('Python 3.10 or newer is required.')
    import pwd
    import grp
    pwd.getpwnam('team04')
    grp.getgrnam('team04')
    if not Path('/run/systemd/system').is_dir():
        raise RuntimeError('A running systemd system is required.')
    for command in ('systemctl', 'systemd-analyze'):
        if not shutil.which(command):
            raise RuntimeError('Required command missing: ' + command)
    for path in (APP, DATA, ENV, UNIT):
        if path.exists() or path.is_symlink():
            raise RuntimeError('Existing path preserved; do not reinstall over it: ' + str(path))
    for path in [*(SERVER/name for name in (*RUNTIME_FILES, 'test_api.py', 'test_scan_windows.py', 'test_contract.py')),
                 ROOT/'sensorae-server.service']:
        if not path.is_file():
            raise RuntimeError('Package file missing: ' + str(path))
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(('0.0.0.0', 8080))
    print('Running isolated API tests before installing...', flush=True)
    run('/usr/bin/python3', '-m', 'unittest', 'discover', '-s', str(SERVER), '-v')
    APP.mkdir(mode=0o755)
    for name in RUNTIME_FILES:
        destination = APP/name
        with destination.open('xb') as output:
            output.write((SERVER/name).read_bytes())
        destination.chmod(0o644)
    fd = os.open(ENV, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as output:
        output.write('APP_TOKEN=' + secrets.token_hex(32) + '\n')
        output.write('ADMIN_TOKEN=' + secrets.token_hex(32) + '\n')
        output.write('SENSORAE_HOST=0.0.0.0\n')
    with UNIT.open('xb') as output:
        output.write((ROOT/'sensorae-server.service').read_bytes())
    UNIT.chmod(0o644)
    run('systemd-analyze', 'verify', str(UNIT))
    run('systemctl', 'daemon-reload')
    run('systemctl', 'enable', '--now', 'sensorae-server.service')
    # Ignore shell HTTP_PROXY settings for a loopback health check.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for attempt in range(15):
        try:
            with opener.open('http://127.0.0.1:8080/health', timeout=2) as response:
                health = json.load(response)
            print('Local health:', json.dumps(health), flush=True)
            run('systemctl', 'is-active', 'sensorae-server.service')
            print('Installed. Listening on LAN TCP 8080; no router or firewall settings changed.')
            print('Tokens are stored in /etc/sensorae-server.env. Do not post their values in chat.')
            return
        except (OSError, ValueError):
            time.sleep(1)
    raise RuntimeError('Health check failed. Inspect: sudo journalctl -u sensorae-server -n 40 --no-pager')

if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, KeyError, subprocess.CalledProcessError) as error:
        print('STOP: ' + str(error), file=sys.stderr)
        print('Existing sensor service and CSV files were not changed. Keep this output for diagnosis.', file=sys.stderr)
        sys.exit(1)
