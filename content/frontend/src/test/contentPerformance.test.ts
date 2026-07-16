import { mount } from "@vue/test-utils";
import { nextTick } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { PostAsset } from "@/api/types";
import ProjectLayoutEditor from "@/features/editor/ProjectLayoutEditor.vue";
import { imagePresentation } from "@/features/mediaProject/mediaPresentation";

function imageAsset(id = "asset"): PostAsset {
  return {
    id, assetId: id, kind: "IMAGE", sourceKind: "UPLOAD", status: "READY", width: 1200, height: 800,
    layout: { assetId: id, x: 0, y: 0, sizePreset: "M", layoutVersion: 1 },
    variants: [480, 960, 1440, 2048].map((width) => ({ name: `image-${width}`, width, height: Math.round(width * 2 / 3), mimeType: "image/webp", url: `/media/${width}` })),
  };
}

afterEach(() => vi.restoreAllMocks());

describe("content performance contracts", () => {
  it("selects responsive canvas variants and reserves 2048 for lightbox", () => {
    const canvas = imagePresentation(imageAsset(), 400, "PROJECT", 2);
    const retinaCanvas = imagePresentation(imageAsset(), 900, "EDITOR", 2);
    const lightbox = imagePresentation(imageAsset(), 1200, "LIGHTBOX", 2);

    expect(canvas.src).toBe("/media/960");
    expect(canvas.srcset).not.toContain("/media/2048");
    expect(retinaCanvas.src).toBe("/media/1440");
    expect(lightbox.src).toBe("/media/2048");
  });

  it("commits one parent document after a coalesced drag", async () => {
    Object.defineProperty(HTMLElement.prototype, "setPointerCapture", { configurable: true, value: vi.fn() });
    Object.defineProperty(HTMLElement.prototype, "releasePointerCapture", { configurable: true, value: vi.fn() });
    let frame: FrameRequestCallback | null = null;
    vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => { frame = callback; return 1; });
    vi.spyOn(window, "cancelAnimationFrame").mockImplementation(() => { frame = null; });
    const wrapper = mount(ProjectLayoutEditor, { props: { modelValue: [imageAsset()] } });
    const asset = wrapper.get(".layout-editor__asset");
    const pointer = (target: Element, type: string, clientX: number, clientY: number) => {
      const event = new MouseEvent(type, { bubbles: true, button: 0, clientX, clientY });
      Object.defineProperty(event, "pointerId", { value: 1 });
      target.dispatchEvent(event);
    };

    pointer(asset.element, "pointerdown", 10, 10);
    pointer(wrapper.get(".layout-editor__canvas").element, "pointermove", 40, 40);
    pointer(wrapper.get(".layout-editor__canvas").element, "pointermove", 80, 80);
    await nextTick();
    expect(wrapper.emitted("update:modelValue")).toBeUndefined();

    const scheduledFrame = frame as FrameRequestCallback | null;
    scheduledFrame?.(16);
    pointer(wrapper.get(".layout-editor__canvas").element, "pointerup", 80, 80);
    await nextTick();
    expect(wrapper.emitted("update:modelValue")).toHaveLength(1);
  });
});
