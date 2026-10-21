import json

with open('notes_app.pen', 'r', encoding='utf-8') as f:
    data = json.load(f)

def summarize_screen(node, depth=0):
    indent = '  ' * depth
    name = node.get('name', '')
    ntype = node.get('type', '')
    txt = node.get('content', '')
    fill = node.get('fill', '')
    icon = node.get('icon', '')
    info = []
    if txt:
        info.append(f'text="{txt}"')
    if icon:
        info.append(f'icon={icon}')
    if fill and not fill.startswith('#00000000'):
        info.append(f'fill={fill}')
    
    extra = (' ' + ' '.join(info)) if info else ''
    print(f'{indent}{ntype} [{name}]{extra}')
    
    if depth < 5:
        for c in node.get('children', []):
            summarize_screen(c, depth + 1)

target_prefixes = ['V2-01', 'V2-02', 'V2-07', 'V2-10', 'V2-14', 'V2-06']
for child in data.get('children', []):
    cname = child.get('name', '')
    for p in target_prefixes:
        if cname.startswith(p):
            print('=' * 60)
            print(f'SCREEN: {cname}')
            print('=' * 60)
            summarize_screen(child)
            break
