import type { CreatePostInput, PostAsset, SavePostDraftInput, UpdatePostInput } from "@/api/types";
import { assetKind, mediaFilesForAssets, serializedAsset } from "@/features/mediaProject/mediaAssets";

export interface MediaEditorState {
  assets: PostAsset[];
  tags: string[];
  allowComments: boolean;
}

export const emptyMediaEditorState = (): MediaEditorState => ({ assets: [], tags: [], allowComments: true });

export function normalizeMediaTags(value: string | string[]): string[] {
  const source = Array.isArray(value) ? value : value.split(/[\s,]+/);
  return [...new Set(source
    .map((tag) => tag.trim().replace(/^#+/, "").toLocaleLowerCase())
    .filter((tag) => /^[\p{L}\p{N}_-]{1,32}$/u.test(tag)))]
    .slice(0, 5);
}

export function mediaPublishability(state: MediaEditorState): string | null {
  if (!state.assets.length) return "Добавьте хотя бы одно медиа.";
  if (state.assets.length > 12) return "В одном проекте может быть до 12 медиа.";
  if (state.assets.some((asset) => !asset.assetId || asset.status === "UPLOADING")) return "Дождитесь завершения загрузки файлов.";
  const broken = state.assets.find((asset) =>
    asset.sourceStatus === "REJECTED"
      || asset.failure?.permanent
      || (asset.status === "FAILED" && asset.sourceStatus !== "AVAILABLE"),
  );
  if (broken) return broken.failure?.userMessage || "Один из файлов повреждён или не поддерживается.";
  if (state.tags.length < 1) return "Добавьте от 1 до 5 хэштегов.";
  return null;
}

export function assetNeedsAction(asset: PostAsset): boolean {
  return asset.sourceStatus === "REJECTED"
    || asset.failure?.permanent === true
    || asset.processingStatus === "FAILED";
}

/** Canonical server data wins, while editor-only object URLs survive autosave.
 * This prevents a freshly uploaded image from blinking out while its stable
 * owner-only source URL is still becoming available. */
export function mergeCanonicalEditorAssets(localAssets: PostAsset[], canonicalAssets: PostAsset[]): PostAsset[] {
  const byItemId = new Map(localAssets.map((asset) => [asset.id, asset]));
  const byAssetId = new Map(localAssets.flatMap((asset) => asset.assetId ? [[asset.assetId, asset] as const] : []));
  return canonicalAssets.map((canonical) => {
    const local = byItemId.get(canonical.id) || (canonical.assetId ? byAssetId.get(canonical.assetId) : undefined);
    return {
      ...canonical,
      clientId: local?.clientId || canonical.clientId,
      previewUrl: local?.previewUrl || canonical.previewUrl || null,
    };
  });
}

export function mediaDraftInput(state: MediaEditorState, id?: string): SavePostDraftInput {
  return {
    ...(id ? { id } : {}),
    text: "",
    blocks: [],
    assets: state.assets.map(serializedAsset),
    tags: normalizeMediaTags(state.tags),
    allowComments: state.allowComments,
    contentVersion: 3,
  };
}

export function mediaCreateInput(state: MediaEditorState): CreatePostInput {
  const reason = mediaPublishability(state);
  if (reason) throw new Error(reason);
  return {
    text: "",
    blocks: [],
    assets: state.assets.map(serializedAsset),
    tags: normalizeMediaTags(state.tags),
    allowComments: state.allowComments,
    contentVersion: 3,
  };
}

export function mediaUpdateInput(id: string, state: MediaEditorState): UpdatePostInput {
  const reason = mediaPublishability(state);
  if (reason) throw new Error(reason);
  return { id, ...mediaCreateInput(state) };
}

export function reorderMedia(assets: PostAsset[], from: number, to: number): PostAsset[] {
  if (from === to || from < 0 || to < 0 || from >= assets.length || to >= assets.length) return assets;
  const next = [...assets];
  const [item] = next.splice(from, 1);
  next.splice(to, 0, item);
  return next;
}

export function acceptedMediaFiles(files: FileList | File[]): File[] {
  return [...files].filter((file) => BROWSER_NATIVE_MEDIA_TYPES.has(file.type.toLocaleLowerCase()));
}

export const BROWSER_NATIVE_MEDIA_TYPES = new Set([
  "image/jpeg", "image/png", "image/webp",
  "video/mp4",
  "audio/mpeg", "audio/mp4", "audio/aac", "audio/x-m4a",
]);

export function isCommentAttachment(asset: PostAsset): boolean {
  return ["IMAGE", "VIDEO"].includes(assetKind(asset));
}

export { mediaFilesForAssets };
