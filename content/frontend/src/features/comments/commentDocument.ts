import type { JSONContent } from "@tiptap/core";
import type { CommentDocumentBlock, CommentDocumentV1, CommentInlineMark, CommentInlineNode, PostAsset } from "@/api/types";

export function emptyCommentDocument(): CommentDocumentV1 { return { version: 1, blocks: [] }; }

export function plainCommentDocument(text: string): CommentDocumentV1 {
  return { version: 1, blocks: text.trim() ? [{ id: crypto.randomUUID(), type: "PARAGRAPH", content: [{ text, marks: [] }] }] : [] };
}

export function commentDocumentToMarkdown(document: CommentDocumentV1): string {
  return document.blocks.map((block) => {
    const text = block.content?.map((node) => node.text).join("") || "";
    if (block.type === "HEADING") return `${"#".repeat(block.level || 2)} ${text}`;
    if (block.type === "QUOTE") return `> ${text}`;
    if (block.type === "CODE") return `\`\`\`${block.language || ""}\n${text}\n\`\`\``;
    if (block.type === "DIVIDER") return "---";
    if (block.type === "BULLET_LIST") return (block.items || []).map((item) => `- ${item}`).join("\n");
    if (block.type === "ORDERED_LIST") return (block.items || []).map((item, index) => `${index + 1}. ${item}`).join("\n");
    if (block.type === "CHECKLIST") return (block.items || []).map((item, index) => `- [${block.checked?.[index] ? "x" : " "}] ${item}`).join("\n");
    if (block.type === "MEDIA") return block.assetId ? `![[media:${block.assetId}]]` : "";
    return text;
  }).filter(Boolean).join("\n\n");
}

export function markdownToCommentDocument(markdown: string): CommentDocumentV1 {
  const blocks: CommentDocumentBlock[] = [];
  const lines = markdown.replace(/\r/g, "").split("\n");
  let index = 0;
  while (index < lines.length && blocks.length < 30) {
    const line = lines[index];
    if (!line.trim()) { index += 1; continue; }
    const heading = line.match(/^(#{2,3})\s+(.+)$/); if (heading) { blocks.push({ id:crypto.randomUUID(),type:"HEADING",level:heading[1].length as 2|3,content:[{text:heading[2],marks:[]}] });index+=1;continue; }
    if (line === "---") { blocks.push({id:crypto.randomUUID(),type:"DIVIDER"});index+=1;continue; }
    if (line.startsWith("```")) { const language=line.slice(3).trim();const body:string[]=[];index+=1;while(index<lines.length&&!lines[index].startsWith("```")){body.push(lines[index]);index+=1;}index+=1;blocks.push({id:crypto.randomUUID(),type:"CODE",language:language||null,content:[{text:body.join("\n"),marks:[]}]});continue; }
    if (line.startsWith("> ")) { blocks.push({id:crypto.randomUUID(),type:"QUOTE",content:[{text:line.slice(2),marks:[]}]});index+=1;continue; }
    const media=line.match(/^!\[\[media:([^\]]+)]]$/);if(media){blocks.push({id:crypto.randomUUID(),type:"MEDIA",assetId:media[1]});index+=1;continue;}
    const checklist=line.match(/^- \[([ xX])\] (.+)$/);if(checklist){const items:string[]=[];const checked:boolean[]=[];while(index<lines.length){const match=lines[index].match(/^- \[([ xX])\] (.+)$/);if(!match)break;checked.push(match[1].toLowerCase()==="x");items.push(match[2]);index+=1;}blocks.push({id:crypto.randomUUID(),type:"CHECKLIST",items,checked});continue;}
    const bullet=line.match(/^- (.+)$/);if(bullet){const items:string[]=[];while(index<lines.length){const match=lines[index].match(/^- (.+)$/);if(!match)break;items.push(match[1]);index+=1;}blocks.push({id:crypto.randomUUID(),type:"BULLET_LIST",items});continue;}
    const ordered=line.match(/^\d+\. (.+)$/);if(ordered){const items:string[]=[];while(index<lines.length){const match=lines[index].match(/^\d+\. (.+)$/);if(!match)break;items.push(match[1]);index+=1;}blocks.push({id:crypto.randomUUID(),type:"ORDERED_LIST",items});continue;}
    blocks.push({id:crypto.randomUUID(),type:"PARAGRAPH",content:[{text:line,marks:[]}]});index+=1;
  }
  return {version:1,blocks};
}

export function commentDocumentText(document: CommentDocumentV1): string {
  return document.blocks.map((block) => [block.content?.map((node) => node.text).join("") || "", ...(block.items || [])].join(" ")).join("\n").trim();
}

export function commentDocumentToTiptap(document: CommentDocumentV1, attachments: PostAsset[]): JSONContent {
  const byAssetId = new Map(attachments.map((asset) => [asset.assetId || asset.id, asset]));
  return { type: "doc", content: document.blocks.flatMap((block): JSONContent[] => {
    if (block.type === "MEDIA" && block.assetId) {
      const asset = byAssetId.get(block.assetId);
      return [{ type: "mediaFile", attrs: { id: asset?.id || block.assetId, name: "media", type: asset?.kind || "IMAGE", url: asset?.previewUrl || asset?.url || "" } }];
    }
    if (block.type === "DIVIDER") return [{ type: "horizontalRule" }];
    if (block.type === "CODE") return [{ type: "codeBlock", attrs: { language: block.language || null }, content: inlineToTiptap(block.content || []) }];
    if (block.type === "QUOTE") return [{ type: "blockquote", content: [{ type: "paragraph", content: inlineToTiptap(block.content || []) }] }];
    if (["BULLET_LIST", "ORDERED_LIST", "CHECKLIST"].includes(block.type)) {
      const task = block.type === "CHECKLIST";
      return [{ type: task ? "taskList" : block.type === "ORDERED_LIST" ? "orderedList" : "bulletList", content: (block.items || []).map((item, index) => ({ type: task ? "taskItem" : "listItem", attrs: task ? { checked: block.checked?.[index] || false } : undefined, content: [{ type: "paragraph", content: [{ type: "text", text: item }] }] })) }];
    }
    return [{ type: block.type === "HEADING" ? "heading" : "paragraph", attrs: block.type === "HEADING" ? { level: block.level || 2 } : undefined, content: inlineToTiptap(block.content || []) }];
  }) };
}

export function tiptapToCommentDocument(root: JSONContent, attachments: PostAsset[]): CommentDocumentV1 {
  const byLocalId = new Map(attachments.map((asset) => [asset.id, asset]));
  const blocks = (root.content || []).flatMap((node): CommentDocumentBlock[] => {
    const id = crypto.randomUUID();
    if (node.type === "mediaFile") {
      const asset = byLocalId.get(String(node.attrs?.id || ""));
      const assetId = asset?.assetId || String(node.attrs?.id || "");
      return assetId ? [{ id, type: "MEDIA", assetId }] : [];
    }
    if (node.type === "horizontalRule") return [{ id, type: "DIVIDER" }];
    if (node.type === "heading") return [{ id, type: "HEADING", level: Number(node.attrs?.level || 2) === 3 ? 3 : 2, content: tiptapInline(node.content) }];
    if (node.type === "blockquote") return [{ id, type: "QUOTE", content: tiptapInline(node.content?.[0]?.content) }];
    if (node.type === "codeBlock") return [{ id, type: "CODE", language: typeof node.attrs?.language === "string" ? node.attrs.language : null, content: tiptapInline(node.content) }];
    if (node.type === "bulletList" || node.type === "orderedList" || node.type === "taskList") {
      return [{ id, type: node.type === "taskList" ? "CHECKLIST" : node.type === "orderedList" ? "ORDERED_LIST" : "BULLET_LIST", items: (node.content || []).map((item) => tiptapInline(item.content?.[0]?.content).map((part) => part.text).join("")), checked: node.type === "taskList" ? (node.content || []).map((item) => Boolean(item.attrs?.checked)) : undefined }];
    }
    return [{ id, type: "PARAGRAPH", content: tiptapInline(node.content) }];
  }).filter((block) => block.type === "MEDIA" || block.type === "DIVIDER" || (block.content?.some((node) => node.text) || block.items?.some(Boolean)));
  return { version: 1, blocks: blocks.slice(0, 30) };
}

function inlineToTiptap(nodes: CommentInlineNode[]): JSONContent[] {
  return nodes.filter((node) => node.text).map((node) => ({
    type: "text",
    text: node.text,
    marks: (node.marks || []).map(markToTiptap).filter((mark): mark is { type: string; attrs?: Record<string, unknown> } => mark !== null),
  }));
}
function markToTiptap(mark: CommentInlineMark): { type: string; attrs?: Record<string, unknown> } | null {
  if (mark.type === "BOLD") return { type: "bold" };
  if (mark.type === "ITALIC") return { type: "italic" };
  if (mark.type === "STRIKE") return { type: "strike" };
  if (mark.type === "INLINE_CODE") return { type: "code" };
  if (mark.type === "LINK" && mark.href) return { type: "link", attrs: { href: mark.href } };
  return null;
}
function tiptapInline(nodes: JSONContent[] = []): CommentInlineNode[] {
  const result: CommentInlineNode[] = [];
  nodes.forEach((node) => {
    if (node.type === "text" && node.text) result.push({ text: node.text, marks: (node.marks || []).map(tiptapMark).filter(Boolean) as CommentInlineMark[] });
    else if (node.type === "hardBreak") result.push({ text: "\n", marks: [] });
    else if (node.content) result.push(...tiptapInline(node.content));
  });
  return result;
}
function tiptapMark(mark: JSONContent): CommentInlineMark | null {
  if (mark.type === "bold") return { type: "BOLD" };
  if (mark.type === "italic") return { type: "ITALIC" };
  if (mark.type === "strike") return { type: "STRIKE" };
  if (mark.type === "code") return { type: "INLINE_CODE" };
  if (mark.type === "link" && typeof mark.attrs?.href === "string") return { type: "LINK", href: mark.attrs.href };
  return null;
}
