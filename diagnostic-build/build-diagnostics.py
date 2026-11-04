#!/usr/bin/env python3
"""Build the independent diagnostic APK using the separately supplied private key archive."""
import os, pathlib, subprocess, sys
root=pathlib.Path(__file__).resolve().parents[1]
if len(sys.argv)<2:
 raise SystemExit('usage: python build-diagnostics.py PATH_TO_SIGNING_FOLDER [Gradle arguments]')
keys=pathlib.Path(sys.argv[1]).resolve()
values=dict(line.split('=',1) for line in (keys/'credentials.txt').read_text().splitlines() if '=' in line)
env=os.environ.copy();env['DSHA_DIAGNOSTIC_KEYSTORE']=str(keys/'dsha-diagnostics.p12');env['DSHA_DIAGNOSTIC_PASSWORD']=values['storePassword']
command=['cmd','/c',str(root/'gradlew.bat')] if os.name=='nt' else ['bash',str(root/'build.sh')]
args=sys.argv[2:] or [':app:assembleStandardDebug']
raise SystemExit(subprocess.call(command+args,cwd=root,env=env))
