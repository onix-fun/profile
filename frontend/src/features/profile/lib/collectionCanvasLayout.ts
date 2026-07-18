import type { ProfileContentPost, SavedCollection } from "@/shared/api/types";

export interface CollectionCanvasSize {
  width: number;
  height: number;
}

export interface CollectionPostNode {
  id: string;
  post: ProfileContentPost;
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface CollectionCenterNode {
  collection: SavedCollection;
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface CollectionCanvasLayout {
  centerNode: CollectionCenterNode;
  postNodes: CollectionPostNode[];
  stage: CollectionCanvasSize;
  initialScrollLeft: number;
}

const CENTER_SIZE: CollectionCanvasSize = { width: 270, height: 198 };
const POST_SIZE: CollectionCanvasSize = { width: 150, height: 150 };
const COLUMN_GAP = 78;
const ROW_GAP = 24;
const SIDE_PADDING = 240;

export function buildCollectionCanvasLayout(
  collection: SavedCollection,
  posts: ProfileContentPost[],
  viewport: CollectionCanvasSize,
): CollectionCanvasLayout {
  const rowPitch = POST_SIZE.height + ROW_GAP;
  const maxRows = Math.max(1, Math.floor((viewport.height - 56) / rowPitch));
  const sideCount = Math.ceil(posts.length / 2);
  const columnsPerSide = Math.max(1, Math.ceil(sideCount / maxRows));
  const sideWidth = columnsPerSide * (POST_SIZE.width + COLUMN_GAP) + SIDE_PADDING;
  const stage = {
    width: Math.max(viewport.width + SIDE_PADDING * 2, sideWidth * 2 + CENTER_SIZE.width),
    height: viewport.height,
  };
  const center = { x: stage.width / 2, y: stage.height / 2 };
  const leftPosts = posts.filter((_, index) => index % 2 === 1);
  const rightPosts = posts.filter((_, index) => index % 2 === 0);

  return {
    centerNode: {
      collection,
      width: CENTER_SIZE.width,
      height: CENTER_SIZE.height,
      x: center.x - CENTER_SIZE.width / 2,
      y: center.y - CENTER_SIZE.height / 2,
    },
    postNodes: [
      ...positionSide(rightPosts, center, 1, maxRows),
      ...positionSide(leftPosts, center, -1, maxRows),
    ],
    stage,
    initialScrollLeft: Math.min(Math.max(0, center.x - viewport.width / 2), Math.max(0, stage.width - viewport.width)),
  };
}

function positionSide(posts: ProfileContentPost[], center: { x: number; y: number }, direction: 1 | -1, maxRows: number): CollectionPostNode[] {
  return posts.map((post, index) => {
    const column = Math.floor(index / maxRows);
    const row = index % maxRows;
    const rowsInColumn = Math.min(maxRows, posts.length - column * maxRows);
    const yStart = center.y - ((rowsInColumn - 1) * (POST_SIZE.height + ROW_GAP)) / 2 - POST_SIZE.height / 2;
    const distance = CENTER_SIZE.width / 2 + COLUMN_GAP + POST_SIZE.width / 2 + column * (POST_SIZE.width + COLUMN_GAP);
    return {
      id: post.id,
      post,
      width: POST_SIZE.width,
      height: POST_SIZE.height,
      x: center.x + direction * distance - POST_SIZE.width / 2,
      y: yStart + row * (POST_SIZE.height + ROW_GAP),
    };
  });
}
