import { Node, mergeAttributes } from "@tiptap/core";
import { VueNodeViewRenderer } from "@tiptap/vue-3";
import MediaFileNodeView from "@/features/contentDocument/MediaFileNodeView.vue";

export interface MediaFileOptions {
  onRemove?: (id: string) => void;
}

declare module "@tiptap/core" {
  interface Commands<ReturnType> {
    mediaFile: {
      insertMediaFile: (attrs: Record<string, unknown>) => ReturnType;
    };
  }
}

export const MediaFile = Node.create<MediaFileOptions>({
  name: "mediaFile",
  group: "block",
  inline: false,
  atom: true,
  selectable: true,
  draggable: true,

  addOptions() {
    return { onRemove: undefined };
  },

  addAttributes() {
    return {
      id: {
        default: "",
        parseHTML: (element) => element.getAttribute("data-id") || element.getAttribute("id") || "",
      },
      name: {
        default: "file",
        parseHTML: (element) => element.getAttribute("data-name") || element.getAttribute("name") || "file",
      },
      type: {
        default: "FILE",
        parseHTML: (element) => element.getAttribute("data-type") || element.getAttribute("type") || "FILE",
      },
      url: {
        default: "",
        parseHTML: (element) => element.getAttribute("data-url") || element.getAttribute("url") || "",
      },
      mimeType: {
        default: "",
        parseHTML: (element) => element.getAttribute("data-mime-type") || element.getAttribute("mimeType") || "",
      },
      size: {
        default: 0,
        parseHTML: (element) => Number(element.getAttribute("data-size") || element.getAttribute("size") || 0),
      },
    };
  },

  parseHTML() {
    return [{ tag: "media-file" }];
  },

  renderHTML({ HTMLAttributes }) {
    return ["media-file", mergeAttributes(HTMLAttributes)];
  },

  addNodeView() {
    return VueNodeViewRenderer(MediaFileNodeView);
  },

  addCommands() {
    return {
      insertMediaFile: (attrs) => ({ commands }) => commands.insertContent({ type: this.name, attrs }),
    };
  },
});
