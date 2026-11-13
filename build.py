"""Builds JCL Core: compiles every .java file (not mixin/ sources, which ship pre-built in precompiled/) against the
libraries in /home/claude/libs/cp.txt, then packs the classes, precompiled/ and resources/ into the jar.
The version comes from GtsMod.java; META-INF/neoforge.mods.toml is taken from the source folder."""
import subprocess, zipfile, os, re, glob, sys
R = os.path.dirname(os.path.abspath(__file__))
ver = re.search(r'VERSION = "([^"]+)"', open(f'{R}/GtsMod.java').read()).group(1)
out = sys.argv[1] if len(sys.argv) > 1 else f'/home/claude/jcl_core-{ver}.jar'
cp = open('/home/claude/libs/cp.txt').read().strip() + ':' + f'{R}/precompiled'
srcs = [f for f in glob.glob(f'{R}/*.java')]
subprocess.run(['rm', '-rf', '/tmp/jcb']); os.makedirs('/tmp/jcb')
r = subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-proc:none', '-nowarn', '-d', '/tmp/jcb', '-cp', cp] + srcs, capture_output=True, text=True)
errs = [l for l in r.stderr.splitlines() if 'error:' in l]
if errs: print('\n'.join(r.stderr.splitlines()[:30])); sys.exit('compile failed: %d errors' % len(errs))
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk('/tmp/jcb'):
        for f in files: p = os.path.join(root, f); z.write(p, os.path.relpath(p, '/tmp/jcb'))
    for base in (f'{R}/precompiled', f'{R}/resources'):
        for root, _, files in os.walk(base):
            for f in files:
                p = os.path.join(root, f); rel = os.path.relpath(p, base)
                if rel == 'META-INF/neoforge.mods.toml': continue
                z.write(p, rel)
    toml = open(f'{R}/neoforge.mods.toml').read()
    z.writestr('META-INF/neoforge.mods.toml', re.sub(r'(\[\[mods\]\]\s*\nmodId\s*=\s*"jcl_(?:gts|core)"\s*\nversion\s*=\s*)"[^"]*"', r'\g<1>"' + ver + '"', toml, count=1))
print('built', out, os.path.getsize(out), 'bytes')
