import type { SetStateAction } from 'jotai';
import { atom, useAtom } from 'jotai';

import type { CloudNotesEditorContainer } from '../../blocksuite/block-suite-editor';

const activeEditorContainerAtom = atom<CloudNotesEditorContainer | null>(null);

export function useActiveBlocksuiteEditor(): [
  CloudNotesEditorContainer | null,
  React.Dispatch<SetStateAction<CloudNotesEditorContainer | null>>,
] {
  const [editorContainer, setEditorContainer] = useAtom(
    activeEditorContainerAtom
  );

  return [editorContainer, setEditorContainer];
}
