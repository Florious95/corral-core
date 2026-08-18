import os,re,sys
pat=re.compile(r'args=|command=|pgrep\s+[-\w]*f|ps\s+[-\w]*\bf')
bad=[]
for root,_,fs in os.walk('server/internal'):
    for f in fs:
        if not f.endswith('.go') or f.endswith('_test.go'): continue
        p=os.path.join(root,f)
        for i,l in enumerate(open(p,encoding='utf-8'),1):
            code=l.split('//')[0]
            if pat.search(code): bad.append(f'{p}:{i}: {l.strip()}')
print('\n'.join(bad))
sys.exit(1 if bad else 0)
