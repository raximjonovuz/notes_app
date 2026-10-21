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
    w = node.get('width', '')
    h = node.get('height', '')
    layout = node.get('layout', '')
    info = []
    if txt:
        info.append(f'text="{txt}"')
    if icon:
        info.append(f'icon={icon}')
    if fill and not fill.startswith('#00000000'):
        info.append(f'fill={fill}')
    if w or h:
        info.append(f'size={w}x{h}')
    if layout:
        info.append(f'layout={layout}')
    
    extra = (' ' + ' '.join(info)) if info else ''
    print(f'{indent}{ntype} [{name}]{extra}')
    
    if depth < 4:
        for c in node.get('children', []):
            summarize_screen(c, depth + 1)

for child in data.get('children', []):
    cname = child.get('name', '')
    if 'V2-01' in cname or 'V2-02' in cname:
        print('=' * 60)
        print(f'SCREEN: {cname}')
        print('=' * 60)
        summarize_screen(child)
