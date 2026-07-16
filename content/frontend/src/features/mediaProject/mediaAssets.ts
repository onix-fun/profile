import type { PostAsset, PostAssetKind, ProfileContentPost } from "@/api/types";

export function assetKind(asset: Pick<PostAsset, "kind" | "mediaType">): PostAssetKind {
  const kind = asset.kind || asset.mediaType;
  return kind === "VIDEO" || kind === "AUDIO" ? kind : "IMAGE";
}

export function assetSource(asset: PostAsset): string {
  if (asset.sourceKind !== "UPLOAD") return "";
  const kind = assetKind(asset);
  const primaryVariants = asset.variants?.filter((variant) => {
    if (kind === "VIDEO") return variant.mimeType?.startsWith("video/");
    if (kind === "AUDIO") return variant.mimeType?.startsWith("audio/");
    return variant.mimeType?.startsWith("image/");
  });
  return asset.previewUrl
    || primaryVariants?.slice().sort((a, b) => (b.width || 0) - (a.width || 0))[0]?.url
    || asset.url
    || asset.posterUrl
    || "";
}

export function assetAspect(asset: PostAsset): number {
  if (asset.width && asset.height && asset.width > 0 && asset.height > 0) return Math.max(0.45, Math.min(2.6, asset.width / asset.height));
  switch (assetKind(asset)) {
    case "VIDEO": return 16 / 9;
    case "AUDIO": return 2.45;
    default: return 4 / 5;
  }
}

export function isAssetReady(asset: PostAsset): boolean {
  return asset.sourceKind === "UPLOAD" && asset.status === "READY";
}

/** Public v2 projects render only MediaStore-backed uploaded assets. */
export function postAssets(post: Pick<ProfileContentPost, "assets">): PostAsset[] {
  return (post.assets || []).filter((asset) =>
    asset.sourceKind === "UPLOAD"
      && (asset.kind === "IMAGE" || asset.kind === "VIDEO" || asset.kind === "AUDIO"),
  );
}

export function fileToPostAsset(file: File): PostAsset {
  const kind: PostAssetKind = file.type.startsWith("video/") ? "VIDEO" : file.type.startsWith("audio/") ? "AUDIO" : "IMAGE";
  const id = crypto.randomUUID();
  return {
    id,
    clientId: id,
    kind,
    sourceKind: "UPLOAD",
    status: "UPLOADING",
    previewUrl: URL.createObjectURL(file),
  };
}

export function serializedAsset(asset: PostAsset): PostAsset {
  const { previewUrl: _previewUrl, ...persisted } = asset;
  return persisted;
}

export function mediaFilesForAssets(assets: PostAsset[], files: Map<string, File>): File[] {
  return assets.flatMap((asset) => {
    const file = files.get(asset.id) || files.get(asset.clientId || "");
    return file ? [file] : [];
  });
}

export function commentMediaAssets(assets: PostAsset[]): PostAsset[] {
  return assets.filter((asset) => ["IMAGE", "VIDEO"].includes(assetKind(asset))).slice(0, 4);
}

export function hasUsableMedia(assets: PostAsset[]): boolean {
  return assets.length > 0
    && assets.length <= 12
    && assets.every((asset) => isAssetReady(asset) && Boolean(asset.assetId));
}

export function mediaStatusLabel(asset: PostAsset): string {
  if (asset.failure) return asset.failure.userMessage;
  if (asset.sourceStatus === "REJECTED") return "Файл повреждён или не поддерживается";
  if (asset.processingStatus === "CANCELLED") return "Обработка остановлена — файл сохранён";
  if (asset.processingStatus === "FAILED") return "Не удалось подготовить медиа";
  if (asset.status === "FAILED") return "Файл отклонён или обработка не удалась";
  if (asset.status === "PROCESSING") return "Создаём версии для публикации";
  if (asset.status === "VERIFYING") return "Проверяем оригинал";
  if (asset.status === "AVAILABLE") return "Оригинал готов";
  if (asset.status === "CANCELLED") return "Обработка отменена";
  if (asset.status === "UPLOADING") return "Загружается";
  return "Готово";
}
