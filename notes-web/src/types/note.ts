export type NoteColorKey =
  | 'default'
  | 'red'
  | 'coral'
  | 'orange'
  | 'peach'
  | 'yellow'
  | 'sand'
  | 'mint'
  | 'green'
  | 'emerald'
  | 'teal'
  | 'sky'
  | 'blue'
  | 'dark_blue'
  | 'lavender'
  | 'purple'
  | 'pink'
  | 'rose'
  | 'brown'
  | 'gray';

export interface NoteColorToken {
  key: NoteColorKey;
  light: string;
  dark: string;
  borderLight: string;
  borderDark: string;
  labelUz: string;
}

export const NOTE_PALETTE: NoteColorToken[] = [
  { key: 'default', light: '#FFFFFF', dark: '#202124', borderLight: '#E0E0E0', borderDark: '#5F6368', labelUz: 'Standart' },
  { key: 'red', light: '#F28B82', dark: '#77172E', borderLight: '#F28B82', borderDark: '#77172E', labelUz: 'Qizil' },
  { key: 'coral', light: '#FF8A80', dark: '#682D27', borderLight: '#FF8A80', borderDark: '#682D27', labelUz: 'Korall' },
  { key: 'orange', light: '#FBBE83', dark: '#6D4C2B', borderLight: '#FBBE83', borderDark: '#6D4C2B', labelUz: "To'q sariq" },
  { key: 'peach', light: '#FFD180', dark: '#5C3E20', borderLight: '#FFD180', borderDark: '#5C3E20', labelUz: 'Shaftolirang' },
  { key: 'yellow', light: '#FFF475', dark: '#534E23', borderLight: '#FFF475', borderDark: '#534E23', labelUz: 'Sariq' },
  { key: 'sand', light: '#FFF9C4', dark: '#4E4820', borderLight: '#FFF9C4', borderDark: '#4E4820', labelUz: 'Qumrang' },
  { key: 'mint', light: '#CCFF90', dark: '#2E4E28', borderLight: '#CCFF90', borderDark: '#2E4E28', labelUz: 'Yalpizrang' },
  { key: 'green', light: '#CCFB90', dark: '#1E4620', borderLight: '#CCFB90', borderDark: '#1E4620', labelUz: 'Yashil' },
  { key: 'emerald', light: '#A7F3D0', dark: '#134E48', borderLight: '#A7F3D0', borderDark: '#134E48', labelUz: 'Zumrad' },
  { key: 'teal', light: '#A7FFEB', dark: '#0F464A', borderLight: '#A7FFEB', borderDark: '#0F464A', labelUz: 'Havorang-yashil' },
  { key: 'sky', light: '#BAE6FD', dark: '#0C4A6E', borderLight: '#BAE6FD', borderDark: '#0C4A6E', labelUz: 'Moviy osmon' },
  { key: 'blue', light: '#CBF0F8', dark: '#09333F', borderLight: '#CBF0F8', borderDark: '#09333F', labelUz: 'Moviy' },
  { key: 'dark_blue', light: '#AFCBFA', dark: '#1B3A6F', borderLight: '#AFCBFA', borderDark: '#1B3A6F', labelUz: "To'q ko'k" },
  { key: 'lavender', light: '#E9D5FF', dark: '#4C1D95', borderLight: '#E9D5FF', borderDark: '#4C1D95', labelUz: 'Lavanda' },
  { key: 'purple', light: '#D7AEFB', dark: '#4A2A62', borderLight: '#D7AEFB', borderDark: '#4A2A62', labelUz: 'Binafsharang' },
  { key: 'pink', light: '#FDCFE8', dark: '#5A2E4A', borderLight: '#FDCFE8', borderDark: '#5A2E4A', labelUz: 'Pushti' },
  { key: 'rose', light: '#FECDD3', dark: '#641E28', borderLight: '#FECDD3', borderDark: '#641E28', labelUz: 'Atirgulrang' },
  { key: 'brown', light: '#E6C9A8', dark: '#513A2D', borderLight: '#E6C9A8', borderDark: '#513A2D', labelUz: 'Jigarrang' },
  { key: 'gray', light: '#E8EAED', dark: '#3C4043', borderLight: '#E8EAED', borderDark: '#3C4043', labelUz: 'Kulrang' },
];

export function getColorToken(key?: string): NoteColorToken {
  const found = NOTE_PALETTE.find(c => c.key === key?.toLowerCase());
  return found || NOTE_PALETTE[0];
}

export interface Note {
  id: string;
  title: string;
  content: string;
  created_at: number;
  updated_at: number;
  client_updated_at?: number;
  change_id: number;
  deleted_at: number | null;
  is_favorite: number; // 0 or 1
  is_trashed: number;  // 0 or 1
  background_color: number;
  background_color_key?: string;
  // Local flags
  isPinned?: boolean;
}

export interface User {
  id: number;
  email: string;
  created_at?: number;
}

// RichDocumentV3 JSON Structure
export type ParagraphKind = 'BODY' | 'H1' | 'H2' | 'CHECKLIST';

export interface ParagraphRun {
  start: number;
  end: number;
  kind: ParagraphKind;
  checked?: boolean;
}

export interface CharSpan {
  start: number;
  end: number;
  bold?: boolean;
  italic?: boolean;
  underline?: boolean;
  strikethrough?: boolean;
  colorKey?: string | null;
}

export interface V3TextBlock {
  t?: 'text';
  text: string;
  paragraphs?: ParagraphRun[];
  spans?: CharSpan[];
}

export interface V3ImageBlock {
  t?: 'img';
  uri: string;
}

export type V3Block = V3TextBlock | V3ImageBlock;

export interface RichDocumentV3 {
  v: 3;
  blocks: V3Block[];
}
