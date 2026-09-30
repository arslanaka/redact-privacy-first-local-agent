/**
 * Converts a DOM selection inside `container` into character offsets of the container's text.
 * Works with any nesting of highlight spans because it counts text-node lengths in document order.
 */
export function selectionOffsets(container: HTMLElement, selection: Selection | null): { start: number; end: number } | null {
  if (!selection || selection.rangeCount === 0 || selection.isCollapsed) return null;
  const range = selection.getRangeAt(0);
  if (!container.contains(range.startContainer) || !container.contains(range.endContainer)) return null;
  const start = offsetOf(container, range.startContainer, range.startOffset);
  const end = offsetOf(container, range.endContainer, range.endOffset);
  if (start === null || end === null || end <= start) return null;
  return { start, end };
}

function offsetOf(container: HTMLElement, node: Node, offset: number): number | null {
  if (node.nodeType !== Node.TEXT_NODE) {
    // Selection boundary on an element: resolve to the text before its offset-th child.
    const children = Array.from(node.childNodes).slice(0, offset);
    const before = children.reduce((n, c) => n + (c.textContent?.length ?? 0), 0);
    return textBefore(container, node) + before;
  }
  return textBefore(container, node) + offset;
}

function textBefore(container: HTMLElement, target: Node): number {
  const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT);
  let total = 0;
  let current = walker.nextNode();
  while (current) {
    if (current === target || target.contains(current)) return total;
    total += current.textContent?.length ?? 0;
    current = walker.nextNode();
  }
  return total;
}
