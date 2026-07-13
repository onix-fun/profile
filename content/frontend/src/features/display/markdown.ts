export interface MarkdownLink {
  label: string;
  href: string;
  raw: string;
  fileLike: boolean;
}

const FILE_EXTENSION_PATTERN = /\.(?:pdf|docx?|xlsx?|pptx?|zip|rar|7z|txt|csv|json|md|mp3|wav|ogg|mp4|mov|webm|png|jpe?g|gif|webp|svg)(?:[?#].*)?$/i;

export function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

export function isFileLikeUrl(href: string, label = ""): boolean {
  const cleanHref = href.trim();
  return cleanHref.startsWith("media:")
    || FILE_EXTENSION_PATTERN.test(cleanHref)
    || FILE_EXTENSION_PATTERN.test(label.trim());
}

export function markdownLinks(markdown: string): MarkdownLink[] {
  const links = Array.from(markdown.matchAll(/\[([^\]]+)\]\(([^)]+)\)/g)).map((match) => ({
    label: match[1],
    href: match[2],
    raw: match[0],
    fileLike: isFileLikeUrl(match[2], match[1]),
  }));
  const wiki = Array.from(markdown.matchAll(/!\[\[media:([^|\]]+)(?:\|([^\]]+))?\]\]/g)).map((match) => ({
    label: match[2] || match[1],
    href: `media:${match[1]}`,
    raw: match[0],
    fileLike: true,
  }));
  return [...links, ...wiki];
}

export function renderInlineMarkdown(markdown: string): string {
  return escapeHtml(markdown)
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/_([^_]+)_/g, "<em>$1</em>")
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\[([^\]]+)\]\(([^)]+)\)/g, (_match, label: string, href: string) => {
      const safeHref = escapeHtml(href);
      if (isFileLikeUrl(href, label)) {
        return `<span class="file-link-inline">${escapeHtml(label)}</span>`;
      }
      return `<a href="${safeHref}" target="_blank" rel="noreferrer">${escapeHtml(label)}</a>`;
    })
    .replace(/!\[\[media:([^|\]]+)(?:\|([^\]]+))?\]\]/g, (_match, id: string, label: string) => (
      `<span class="file-link-inline">${escapeHtml(label || id)}</span>`
    ))
    .replace(/(^|\s)#([\p{L}\p{N}_-]+)/gu, '$1<span class="tag-inline">#$2</span>')
    .replace(/(^|\s)@([\p{L}\p{N}_-]+)/gu, '$1<span class="mention-inline">@$2</span>');
}
