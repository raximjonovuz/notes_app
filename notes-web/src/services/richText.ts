import type { RichDocumentV3, V3TextBlock, V3ImageBlock } from '../types/note';

export interface ChecklistItem {
  id: string;
  text: string;
  checked: boolean;
}

export function parseNoteContent(raw: string): {
  isV3: boolean;
  v3Doc: RichDocumentV3 | null;
  displayText: string;
  checklists: ChecklistItem[];
  images: string[];
} {
  if (!raw || typeof raw !== 'string') {
    return { isV3: false, v3Doc: null, displayText: '', checklists: [], images: [] };
  }

  const trimmed = raw.trimStart();
  if (trimmed.startsWith('{"v":3') || trimmed.startsWith('{"v": 3')) {
    try {
      const doc = JSON.parse(trimmed) as RichDocumentV3;
      if (doc && doc.v === 3 && Array.isArray(doc.blocks)) {
        const textBlocks = doc.blocks.filter((b): b is V3TextBlock => !('uri' in b));
        const imageBlocks = doc.blocks.filter((b): b is V3ImageBlock => 'uri' in b);
        const images = imageBlocks.map(b => b.uri);

        const checklists: ChecklistItem[] = [];
        let displayText = '';

        for (const tb of textBlocks) {
          const text = tb.text || '';
          if (tb.paragraphs && tb.paragraphs.length > 0) {
            for (let i = 0; i < tb.paragraphs.length; i++) {
              const p = tb.paragraphs[i];
              const seg = text.slice(p.start, p.end);
              if (p.kind === 'CHECKLIST') {
                checklists.push({
                  id: `chk_${i}_${p.start}`,
                  text: seg,
                  checked: !!p.checked,
                });
              } else {
                displayText += (displayText ? '\n' : '') + seg;
              }
            }
          } else {
            // Check if plain text lines have markdown checklists
            const lines = text.split('\n');
            const normalLines: string[] = [];
            lines.forEach((l, idx) => {
              const chk = parseChecklistLine(l);
              if (chk.isChecklist) {
                checklists.push({
                  id: `chk_${idx}`,
                  text: chk.text,
                  checked: chk.checked,
                });
              } else {
                normalLines.push(l);
              }
            });
            displayText = normalLines.join('\n');
          }
        }

        return { isV3: true, v3Doc: doc, displayText, checklists, images };
      }
    } catch {
      // fallback to plain text parsing
    }
  }

  // Plain text / markdown checklists fallback
  const lines = raw.split('\n');
  const checklists: ChecklistItem[] = [];
  const normalLines: string[] = [];
  const images: string[] = [];

  lines.forEach((l, idx) => {
    // Check for images like IMAGE|url
    if (l.startsWith('IMAGE|')) {
      images.push(l.substring(6).trim());
      return;
    }
    const chk = parseChecklistLine(l);
    if (chk.isChecklist) {
      checklists.push({
        id: `chk_${idx}`,
        text: chk.text,
        checked: chk.checked,
      });
    } else {
      normalLines.push(l);
    }
  });

  return {
    isV3: false,
    v3Doc: null,
    displayText: normalLines.join('\n'),
    checklists,
    images,
  };
}

export function parseChecklistLine(line: string): { isChecklist: boolean; checked: boolean; text: string } {
  const trimmed = line.trimStart();
  if (trimmed.startsWith('- [ ] ') || trimmed.startsWith('- [ ]')) {
    return { isChecklist: true, checked: false, text: trimmed.replace(/^- \[\s?\]\s?/, '') };
  }
  if (trimmed.startsWith('- [x] ') || trimmed.startsWith('- [x]') || trimmed.startsWith('- [X] ') || trimmed.startsWith('- [X]')) {
    return { isChecklist: true, checked: true, text: trimmed.replace(/^- \[[xX]\s?\]\s?/, '') };
  }
  return { isChecklist: false, checked: false, text: line };
}

export function buildV3Content(
  plainText: string,
  checklists: ChecklistItem[],
  images: string[] = []
): string {
  // If no checklists and no images, return plain text
  if (checklists.length === 0 && images.length === 0) {
    return plainText;
  }

  // Construct RichDocumentV3
  let fullText = '';
  const paragraphs: Array<{ start: number; end: number; kind: 'BODY' | 'CHECKLIST'; checked?: boolean }> = [];

  if (plainText.trim()) {
    const start = fullText.length;
    fullText += plainText;
    paragraphs.push({
      start,
      end: fullText.length,
      kind: 'BODY',
    });
  }

  for (const item of checklists) {
    if (!item.text && item.text !== '') continue;
    if (fullText.length > 0) fullText += '\n';
    const start = fullText.length;
    fullText += item.text;
    paragraphs.push({
      start,
      end: fullText.length,
      kind: 'CHECKLIST',
      checked: item.checked,
    });
  }

  const blocks: any[] = [];
  if (fullText.length > 0 || paragraphs.length > 0) {
    blocks.push({
      t: 'text',
      text: fullText,
      paragraphs,
      spans: [],
    });
  }

  for (const img of images) {
    blocks.push({
      t: 'img',
      uri: img,
    });
  }

  const doc: RichDocumentV3 = {
    v: 3,
    blocks,
  };

  return JSON.stringify(doc);
}
