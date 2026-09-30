import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { BulkApproveButton } from "@/components/bulk-approve-button";
import { ConfidenceIndicator } from "@/components/confidence-indicator";
import { DocumentText } from "@/components/document-text";
import { TooltipProvider } from "@/components/ui/tooltip";
import type { EntityDto } from "@/lib/types";

function entity(partial: Partial<EntityDto>): EntityDto {
  return {
    id: "e1",
    documentId: "d1",
    text: "Anna Keller",
    type: "PERSON",
    confidence: 0.97,
    confidenceLevel: "HIGH",
    startOffset: 8,
    endOffset: 19,
    source: "LAYA",
    reviewStatus: "PENDING",
    recommendation: "REDACT",
    recommendationReason: "",
    createdAt: "",
    ...partial,
  };
}

describe("ConfidenceIndicator", () => {
  it("shows the group and the raw percentage", () => {
    render(
      <TooltipProvider>
        <ConfidenceIndicator confidence={0.82} />
      </TooltipProvider>,
    );
    expect(screen.getByText("82%")).toBeInTheDocument();
    expect(screen.getByText("Medium")).toBeInTheDocument();
    expect(screen.getByLabelText("Medium confidence, 82%")).toBeInTheDocument();
  });
});

describe("BulkApproveButton", () => {
  const entities = [
    entity({ id: "a" }),
    entity({ id: "b", text: "Berlin", type: "LOCATION", recommendation: "REVIEW" }),
    entity({ id: "c", confidence: 0.75, confidenceLevel: "MEDIUM" }),
    entity({ id: "d", reviewStatus: "REJECTED" }),
  ];

  it("counts only pending, high-confidence items recommended for redaction", () => {
    render(<BulkApproveButton entities={entities} onConfirm={vi.fn()} />);
    expect(screen.getByRole("button", { name: "Approve all high confidence (1)" })).toBeEnabled();
  });

  it("requires explicit confirmation before approving", async () => {
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    render(<BulkApproveButton entities={entities} onConfirm={onConfirm} />);
    fireEvent.click(screen.getByRole("button", { name: /Approve all high confidence/ }));
    expect(onConfirm).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByRole("button", { name: "Cancel" }));
    expect(onConfirm).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: /Approve all high confidence/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Yes, approve 1" }));
    await waitFor(() => expect(onConfirm).toHaveBeenCalledTimes(1));
  });

  it("is disabled when nothing qualifies", () => {
    render(<BulkApproveButton entities={[entities[1]]} onConfirm={vi.fn()} />);
    expect(screen.getByRole("button", { name: /Approve all high confidence/ })).toBeDisabled();
  });
});

describe("DocumentText", () => {
  it("highlights entities by review status and reports clicks", () => {
    const onClick = vi.fn();
    const text = "Contact Anna Keller in Berlin.";
    render(
      <DocumentText
        text={text}
        entities={[entity({}), entity({ id: "e2", text: "Berlin", startOffset: 23, endOffset: 29, reviewStatus: "REJECTED" })]}
        onEntityClick={onClick}
      />,
    );
    expect(screen.getByTestId("document-text")).toHaveTextContent(text);
    const marks = document.querySelectorAll("mark");
    expect(marks).toHaveLength(2);
    expect(marks[1].className).toContain("line-through");
    fireEvent.click(screen.getByText("Anna Keller"));
    expect(onClick).toHaveBeenCalledWith("e1");
  });
});
