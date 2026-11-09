import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { App } from "../src/App";
import restatements from "../src/fixtures/restatements.json";
import type { RestatementDiff } from "../src/types";

const DIFFS = restatements as RestatementDiff[];

describe("App", () => {
  it("renders a card for every restated fact in the fixture", () => {
    render(<App />);
    for (const diff of DIFFS) {
      expect(screen.getByTestId(`restatement-${diff.factId}`)).toBeInTheDocument();
    }
  });

  it("names every field the backend diffed, for every fact, with its prior and restated value", () => {
    render(<App />);
    for (const diff of DIFFS) {
      for (const field of diff.fields) {
        const row = screen.getByTestId(`field-${diff.factId}-${field.fieldName}`);
        expect(row).toHaveTextContent(field.fieldName);
        expect(row).toHaveTextContent(field.priorValue);
        expect(row).toHaveTextContent(field.restatedValue);
      }
    }
  });

  it("renders at least one field that actually changed and at least one that did not, across the fixture", () => {
    render(<App />);
    const allFields = DIFFS.flatMap((d) => d.fields);
    expect(allFields.some((f) => f.changed)).toBe(true);
    expect(allFields.some((f) => !f.changed)).toBe(true);
  });
});
