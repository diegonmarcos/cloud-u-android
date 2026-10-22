import {
  Divider,
  DragHandle,
  type InlineEditHandle,
  observeResize,
  useDraggable,
} from '@cloud-notes/component';
import { FavoriteButton } from '@cloud-notes/core/blocksuite/block-suite-header/favorite';
import { InfoButton } from '@cloud-notes/core/blocksuite/block-suite-header/info';
import { JournalWeekDatePicker } from '@cloud-notes/core/blocksuite/block-suite-header/journal/date-picker';
import { JournalTodayButton } from '@cloud-notes/core/blocksuite/block-suite-header/journal/today-button';
import { PageHeaderMenuButton } from '@cloud-notes/core/blocksuite/block-suite-header/menu';
import { DetailPageHeaderPresentButton } from '@cloud-notes/core/blocksuite/block-suite-header/present/detail-header-present-button';
import { BlocksuiteHeaderTitle } from '@cloud-notes/core/blocksuite/block-suite-header/title';
import { EditorModeSwitch } from '@cloud-notes/core/blocksuite/block-suite-mode-switch';
import { useRegisterCopyLinkCommands } from '@cloud-notes/core/components/hooks/cloud-notes/use-register-copy-link-commands';
import { HeaderDivider } from '@cloud-notes/core/components/pure/header';
import { DocService } from '@cloud-notes/core/modules/doc';
import { DocDisplayMetaService } from '@cloud-notes/core/modules/doc-display-meta';
import { EditorService } from '@cloud-notes/core/modules/editor';
import { JournalService } from '@cloud-notes/core/modules/journal';
import { SharePageButton } from '@cloud-notes/core/modules/share-menu';
import { TemplateDocService } from '@cloud-notes/core/modules/template-doc';
import { ViewIcon, ViewTitle } from '@cloud-notes/core/modules/workbench';
import type { Workspace } from '@cloud-notes/core/modules/workspace';
import type { CloudNotesDNDData } from '@cloud-notes/core/types/dnd';
import { useI18n } from '@cloud-notes/i18n';
import { track } from '@cloud-notes/track';
import type { Store } from '@blocksuite/cloud-notes/store';
import { useLiveData, useService } from '@toeverything/infra';
import clsx from 'clsx';
import {
  forwardRef,
  type HTMLAttributes,
  memo,
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react';

import * as styles from './detail-page-header.css';
import { useDetailPageHeaderResponsive } from './use-header-responsive';

const Header = forwardRef<
  HTMLDivElement,
  {
    children: React.ReactNode;
    className?: string;
    style?: React.CSSProperties;
  }
>(({ children, style, className }, ref) => {
  return (
    <div data-testid="header" style={style} className={className} ref={ref}>
      {children}
    </div>
  );
});

Header.displayName = 'forwardRef(Header)';

const TemplateMark = memo(function TemplateMark({
  className,
  ...props
}: HTMLAttributes<HTMLDivElement>) {
  const t = useI18n();
  const doc = useService(DocService).doc;
  const templateDocService = useService(TemplateDocService);
  const isTemplate = useLiveData(templateDocService.list.isTemplate$(doc.id));

  if (!isTemplate) return null;

  return (
    <div className={clsx(styles.templateMark, className)} {...props}>
      {t['Template']()}
    </div>
  );
});

interface PageHeaderProps {
  page: Store;
  workspace: Workspace;
}
export function JournalPageHeader({ page, workspace }: PageHeaderProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [containerWidth, setContainerWidth] = useState(0);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    return observeResize(container, entry => {
      setContainerWidth(entry.contentRect.width);
    });
  }, []);

  const { hideShare, hideToday } =
    useDetailPageHeaderResponsive(containerWidth);

  const docDisplayMetaService = useService(DocDisplayMetaService);
  const title = useLiveData(docDisplayMetaService.title$(page.id));

  return (
    <Header className={styles.header} ref={containerRef}>
      <ViewTitle title={title} />
      <ViewIcon icon="journal" />
      <EditorModeSwitch />
      <div className={styles.journalWeekPicker}>
        <JournalWeekDatePicker page={page} />
      </div>
      <TemplateMark className={styles.journalTemplateMark} />
      {hideToday ? null : <JournalTodayButton />}
      <HeaderDivider />
      <PageHeaderMenuButton
        isJournal
        page={page}
        containerWidth={containerWidth}
      />
      {page && !hideShare ? (
        <SharePageButton workspace={workspace} page={page} />
      ) : null}
    </Header>
  );
}

export function NormalPageHeader({ page, workspace }: PageHeaderProps) {
  const titleInputHandleRef = useRef<InlineEditHandle>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [containerWidth, setContainerWidth] = useState(0);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    return observeResize(container, entry => {
      setContainerWidth(entry.contentRect.width);
    });
  }, []);

  const { hideCollect, hideShare, hidePresent, showDivider } =
    useDetailPageHeaderResponsive(containerWidth);

  const onRename = useCallback(() => {
    setTimeout(
      () => titleInputHandleRef.current?.triggerEdit(),
      500 /* wait for menu animation end */
    );
  }, []);

  const docDisplayMetaService = useService(DocDisplayMetaService);
  const title = useLiveData(docDisplayMetaService.title$(page.id));

  const editor = useService(EditorService).editor;
  const currentMode = useLiveData(editor.mode$);

  return (
    <Header className={styles.header} ref={containerRef}>
      <ViewTitle title={title} />
      <ViewIcon icon={currentMode ?? 'page'} />
      <EditorModeSwitch />
      <BlocksuiteHeaderTitle inputHandleRef={titleInputHandleRef} />
      <TemplateMark />
      <div className={styles.iconButtonContainer}>
        {hideCollect ? null : (
          <>
            <FavoriteButton pageId={page?.id} />
            <InfoButton docId={page.id} />
          </>
        )}
        <PageHeaderMenuButton
          rename={onRename}
          page={page}
          containerWidth={containerWidth}
        />
      </div>

      <div className={styles.spacer} />

      {!hidePresent ? <DetailPageHeaderPresentButton /> : null}

      {page && !hideShare ? (
        <SharePageButton workspace={workspace} page={page} />
      ) : null}

      {showDivider ? (
        <Divider orientation="vertical" style={{ height: 20, marginLeft: 4 }} />
      ) : null}
    </Header>
  );
}

export function DetailPageHeader(
  props: PageHeaderProps & {
    onDragging?: (dragging: boolean) => void;
  }
) {
  const { page, workspace, onDragging } = props;
  const journalService = useService(JournalService);
  const isJournal = !!useLiveData(journalService.journalDate$(page.id));
  const isInTrash = page.meta?.trash;

  useRegisterCopyLinkCommands({
    workspaceMeta: workspace.meta,
    docId: page.id,
  });

  const { dragRef, dragging, CustomDragPreview } =
    useDraggable<CloudNotesDNDData>(() => {
      return {
        data: {
          from: {
            at: 'doc-detail:header',
            docId: page.id,
          },
          entity: {
            type: 'doc',
            id: page.id,
          },
        },
        canDrag: args => {
          // hack for preventing drag when editing the page title
          const editingElement =
            args.element.contains(document.activeElement) &&
            document.activeElement?.tagName === 'INPUT';
          return !editingElement;
        },
        onDragStart: () => {
          track.$.header.$.dragStart();
        },
        dragPreviewPosition: 'pointer-outside',
      };
    }, [page.id]);

  const inner =
    isJournal && !isInTrash ? (
      <JournalPageHeader {...props} />
    ) : (
      <NormalPageHeader {...props} />
    );

  useEffect(() => {
    onDragging?.(dragging);
  }, [dragging, onDragging]);

  return (
    <>
      <div className={styles.root} ref={dragRef} data-dragging={dragging}>
        <DragHandle dragging={dragging} className={styles.dragHandle} />
        {inner}
      </div>
      <CustomDragPreview>
        <div className={styles.dragPreview}>{inner}</div>
      </CustomDragPreview>
    </>
  );
}
