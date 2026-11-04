#!/usr/bin/env python3
"""Execute the production pure-Java entry policy; no Android/model implied."""
import os, pathlib, subprocess, tempfile

root = pathlib.Path(__file__).resolve().parents[1]
source = root / 'app/src/main/java/com/deepseekharness/app/util/ChatEntryPolicy.java'
test = r'''
import com.deepseekharness.app.util.ChatEntryPolicy;
public class ChatEntryPolicyCheck {
  static int checks;
  static void check(String want, boolean busy, boolean pending, boolean unknown,
      boolean stopped, boolean failed, boolean web, boolean maintenance, boolean attempted) {
    String got = ChatEntryPolicy.action(busy,pending,unknown,stopped,failed,web,maintenance,attempted).name();
    if (!want.equals(got)) throw new AssertionError(want+" != "+got);
    checks++;
  }
  public static void main(String[] args) {
    check("WAIT",true,false,false,false,false,true,false,false);
    check("RECOVER",false,true,false,false,false,true,false,false);
    check("CHECK",false,false,true,false,false,true,false,false);
    check("STOPPED",false,false,false,true,false,true,false,false);
    check("READY",false,false,false,false,false,true,false,false);
    check("PREPARE",false,false,false,false,false,false,true,false);
    check("ERROR",false,false,false,false,false,false,true,true);
    check("ERROR",false,false,false,false,true,false,true,false);
    check("ERROR",false,false,false,false,true,false,false,false);
    check("START",false,false,false,false,false,false,false,false);
    check("WAIT",true,true,true,true,true,true,true,true);
    check("RECOVER",false,true,true,true,true,true,true,true);
    check("CHECK",false,false,true,true,true,true,true,true);
    check("ERROR",false,false,false,true,true,true,true,true);
    check("READY",false,false,false,false,true,true,false,true);
    check("ERROR",false,false,false,true,true,false,false,true);
    check("ERROR",false,false,false,true,false,false,true,false);
    System.out.println("PASS "+checks+" entry-policy cases");
  }
}
'''
with tempfile.TemporaryDirectory() as temp:
    path = pathlib.Path(temp)
    (path/'ChatEntryPolicyCheck.java').write_text(test)
    compiler = ['java','-jar',os.environ['DSHA_ECJ'],'-17'] if os.environ.get('DSHA_ECJ') else ['javac']
    subprocess.run(compiler+['-d',temp,str(source),str(path/'ChatEntryPolicyCheck.java')],check=True)
    subprocess.run(['java','-cp',temp,'ChatEntryPolicyCheck'],check=True)
