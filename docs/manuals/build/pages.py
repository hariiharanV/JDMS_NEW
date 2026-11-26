import json, re, sys
text = open(sys.argv[1], encoding='utf-8').read().split('\f')
src = open(sys.argv[2], encoding='utf-8').read()
heads = [h.replace("\\'", "'") for h in re.findall(r"\bh[12](?:nb)?\('((?:[^'\\]|\\.)*)'\)", src)]
out = {}
for h in heads:
    for i, page in enumerate(text):
        if i < 2:  # cover and contents
            continue
        if any(line.strip().startswith(h) for line in page.splitlines()):
            out[h] = i + 1
            break
print(json.dumps(out, ensure_ascii=False))
