#!/usr/bin/env python3
"""Copy an APK payload while removing generated asset staging files.

Re-align and re-sign the output afterwards.  The runtime generators use both
``*.tmp`` and hidden ``.dsh-runtime.bin.*`` names for atomic writes; neither is
part of the managed APK asset contract.
"""
import pathlib,sys,zipfile,hashlib
if len(sys.argv)!=3:raise SystemExit('usage: strip-temporary-assets.py input.apk unsigned-output.apk')
source,target=map(pathlib.Path,sys.argv[1:])
def is_staging_asset(name):
 return (name.startswith('assets/') and name.endswith('.tmp')) or name.startswith('assets/.dsh-runtime.bin.')
with zipfile.ZipFile(source) as zin,zipfile.ZipFile(target,'w') as zout:
 for info in zin.infolist():
  if is_staging_asset(info.filename):continue
  if info.filename.startswith('META-INF/') and info.filename.endswith(('.RSA','.DSA','.EC','.SF','.MF')):continue
  data=zin.read(info.filename);zout.writestr(info,data)
with zipfile.ZipFile(source) as zin,zipfile.ZipFile(target) as zout:
 for name in zout.namelist():assert hashlib.sha256(zin.read(name)).digest()==hashlib.sha256(zout.read(name)).digest()
 assert zout.testzip() is None
print('Verified all retained APK entries byte-for-byte; align and re-sign required')
