import type { PostAsset } from "@/api/types";

type AssetVariant = NonNullable<PostAsset["variants"]>[number];

export type MediaRenderContext = "EDITOR" | "PROJECT" | "FEED" | "LIGHTBOX";

export interface ImagePresentation {
  src: string;
  srcset?: string;
  sizes?: string;
}

function variantWidth(variant: AssetVariant): number {
  const named = variant.name?.match(/image-(\d+)/)?.[1];
  return variant.width || (named ? Number(named) : 0);
}

function variantTier(variant: AssetVariant): number {
  const named = variant.name?.match(/image-(\d+)/)?.[1];
  return named ? Number(named) : variantWidth(variant);
}

export function imagePresentation(
  asset: PostAsset,
  cssWidth: number,
  context: MediaRenderContext,
  devicePixelRatio = typeof window === "undefined" ? 1 : window.devicePixelRatio || 1,
): ImagePresentation {
  if (asset.previewUrl) return { src: asset.previewUrl };
  const cap = context === "LIGHTBOX" ? 2048 : 1440;
  const variants = (asset.variants || [])
    .filter((variant) => Boolean(variant.url)
      && (variant.mimeType?.startsWith("image/") || variant.name?.startsWith("image-"))
      && variantTier(variant) <= cap)
    .sort((left, right) => variantWidth(left) - variantWidth(right));
  if (!variants.length) return { src: asset.url || asset.posterUrl || "" };
  const target = Math.min(cap, Math.max(1, cssWidth) * Math.min(2, Math.max(1, devicePixelRatio)));
  const selected = variants.find((variant) => variantWidth(variant) >= target) || variants.at(-1)!;
  return {
    src: selected.url,
    srcset: variants.map((variant) => `${variant.url} ${variantWidth(variant)}w`).join(", "),
    sizes: `${Math.max(1, Math.ceil(cssWidth))}px`,
  };
}
