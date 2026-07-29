const focusableSelector = [
  "a[href]",
  "button:not([disabled])",
  "input:not([disabled])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  "[tabindex]:not([tabindex='-1'])",
].join(",");

export function activateFocusTrap(container: HTMLElement, onEscape: () => void): () => void {
  const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  const focusable = () => [...container.querySelectorAll<HTMLElement>(focusableSelector)].filter((element) => !element.hidden);

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === "Escape") {
      event.preventDefault();
      onEscape();
      return;
    }
    if (event.key !== "Tab") return;
    const elements = focusable();
    if (!elements.length) {
      event.preventDefault();
      container.focus();
      return;
    }
    const first = elements[0]!;
    const last = elements[elements.length - 1]!;
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  container.addEventListener("keydown", onKeyDown);
  (focusable()[0] || container).focus();
  return () => {
    container.removeEventListener("keydown", onKeyDown);
    previous?.focus();
  };
}
