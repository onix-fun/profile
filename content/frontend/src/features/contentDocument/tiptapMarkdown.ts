import type { JSONContent } from "@tiptap/vue-3";
import { attachmentMarkdown } from "@/features/contentDocument/contentModel";

function markText(text: string, marks: JSONContent["marks"] = []): string {
  return [...(marks || [])].reverse().reduce((value, mark) => {
    if (mark.type === "bold") return `**${value}**`;
    if (mark.type === "italic") return `_${value}_`;
    if (mark.type === "code") return `\`${value}\``;
    if (mark.type === "link" && typeof mark.attrs?.href === "string") return `[${value}](${mark.attrs.href})`;
    return value;
  }, text);
}

function inlineMarkdown(nodes: JSONContent[] = []): string {
  return nodes.map((node) => {
    if (node.type === "text") return markText(node.text || "", node.marks);
    if (node.type === "hardBreak") return "\n";
    if (node.type === "mediaFile") {
      return attachmentMarkdown({ id: String(node.attrs?.id || ""), name: String(node.attrs?.name || "file") });
    }
    return inlineMarkdown(node.content || []);
  }).join("");
}

function blockMarkdown(node: JSONContent): string {
  if (node.type === "heading") {
    const level = Math.min(Math.max(Number(node.attrs?.level || 1), 1), 3);
    return `${"#".repeat(level)} ${inlineMarkdown(node.content || [])}`.trimEnd();
  }
  if (node.type === "paragraph") return inlineMarkdown(node.content || []).trimEnd();
  if (node.type === "blockquote") {
    return (node.content || []).map(blockMarkdown).join("\n").split("\n").map((line) => `> ${line}`).join("\n");
  }
  if (node.type === "bulletList") {
    return (node.content || []).map((item) => `- ${inlineMarkdown(item.content?.[0]?.content || [])}`).join("\n");
  }
  if (node.type === "orderedList") {
    return (node.content || []).map((item, index) => `${index + 1}. ${inlineMarkdown(item.content?.[0]?.content || [])}`).join("\n");
  }
  if (node.type === "mediaFile") return inlineMarkdown([node]);
  return inlineMarkdown(node.content || []);
}

export function editorJsonToMarkdown(doc: JSONContent): string {
  return (doc.content || [])
    .map(blockMarkdown)
    .join("\n\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

function inlineHtml(markdown: string): string {
  const escaped = markdown
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
  return escaped
    .replace(/!\[\[media:([^|\]]+)(?:\|([^\]]+))?\]\]/g, (_match, id: string, label: string) => (
      `<media-file data-id="${id}" data-name="${label || id}"></media-file>`
    ))
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/_([^_]+)_/g, "<em>$1</em>")
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2">$1</a>');
}

export function markdownToEditorHtml(markdown: string): string {
  if (!markdown.trim()) return "";
  return markdown.split(/\n{2,}/).map((block) => {
    const lines = block.split("\n");
    const heading = block.match(/^(#{1,3})\s+(.+)$/);
    if (heading) return `<h${heading[1].length}>${inlineHtml(heading[2])}</h${heading[1].length}>`;
    if (lines.every((line) => /^-\s+/.test(line))) {
      return `<ul>${lines.map((line) => `<li>${inlineHtml(line.replace(/^-\s+/, ""))}</li>`).join("")}</ul>`;
    }
    if (lines.every((line) => /^>\s?/.test(line))) {
      return `<blockquote>${inlineHtml(lines.map((line) => line.replace(/^>\s?/, "")).join("\n"))}</blockquote>`;
    }
    return `<p>${inlineHtml(block).replace(/\n/g, "<br>")}</p>`;
  }).join("");
}
