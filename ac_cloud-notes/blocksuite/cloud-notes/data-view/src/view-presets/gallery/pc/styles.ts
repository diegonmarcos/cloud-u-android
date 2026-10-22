import { css } from 'lit';

export const galleryViewStyles = css`
  cloud-notes-data-view-gallery {
    display: block;
    width: 100%;
    box-sizing: border-box;
    --gallery-card-width: 220px;
  }
  cloud-notes-data-view-gallery[data-size='small'] {
    --gallery-card-width: 160px;
  }
  cloud-notes-data-view-gallery[data-size='large'] {
    --gallery-card-width: 300px;
  }
  .gallery-grid {
    display: grid;
    grid-template-columns: repeat(
      auto-fill,
      minmax(var(--gallery-card-width), 1fr)
    );
    gap: 12px;
    padding: 8px 0 12px;
  }
  .gallery-card {
    display: flex;
    flex-direction: column;
    overflow: hidden;
    cursor: pointer;
    border: 1px solid var(--affine-border-color);
    border-radius: 8px;
    background: var(--affine-background-primary-color);
  }
  .gallery-card:hover {
    background: var(--affine-hover-color);
  }
  .gallery-cover {
    width: 100%;
    aspect-ratio: 16 / 10;
    object-fit: cover;
    background: var(--affine-background-secondary-color);
  }
  .gallery-cover-empty {
    width: 100%;
    aspect-ratio: 16 / 10;
    background: var(--affine-background-secondary-color);
  }
  .gallery-body {
    padding: 8px 10px 10px;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .gallery-title {
    font-weight: 600;
    font-size: 14px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .gallery-prop {
    font-size: 12px;
    color: var(--affine-text-secondary-color);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .gallery-add {
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 80px;
    border: 1px dashed var(--affine-border-color);
    border-radius: 8px;
    color: var(--affine-text-secondary-color);
    cursor: pointer;
  }
  .gallery-empty {
    padding: 16px;
    color: var(--affine-text-secondary-color);
  }
`;
