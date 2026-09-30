import { describe, expect, it } from "vitest";
import { selectionOffsets } from "@/lib/selection";

function setup(html: string) {
  const container = document.createElement("div");
  container.innerHTML = html;
  document.body.appendChild(container);
  return container;
}

describe("selectionOffsets", () => {
  it("maps a selection across highlight elements to text offsets", () => {
    const c = setup("<span>Contact </span><mark>Anna Keller</mark><span> today at noon</span>");
    const range = document.createRange();
    range.setStart(c.querySelector("mark")!.firstChild!, 5); // "Keller"
    range.setEnd(c.querySelectorAll("span")[1].firstChild!, 6); // " today"
    const sel = window.getSelection()!;
    sel.removeAllRanges();
    sel.addRange(range);
    const offsets = selectionOffsets(c, sel);
    expect(offsets).toEqual({ start: 13, end: 25 });
    expect(c.textContent!.slice(offsets!.start, offsets!.end)).toBe("Keller today");
  });

  it("returns null for collapsed selections or selections outside the container", () => {
    const c = setup("<span>hello</span>");
    const other = setup("<span>elsewhere</span>");
    const sel = window.getSelection()!;
    sel.removeAllRanges();
    expect(selectionOffsets(c, sel)).toBeNull();
    const range = document.createRange();
    range.selectNodeContents(other);
    sel.addRange(range);
    expect(selectionOffsets(c, sel)).toBeNull();
  });
});
