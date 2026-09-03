import type { Root, Text } from "mdast";
import type { HTMLAttributes } from "react";
import type { Parent } from "unist";
import { visit } from "unist-util-visit";

/** Matches the bracketed references the model is asked to produce: [1] or [2, 3]. */
const CITATION = /\[(\d+(?:\s*,\s*\d+)*)\]/g;

/** A citation reference, carried through the mdast/hast pipeline as its own node. */
export interface CitationNode {
  type: "citation";
  numbers: number[];
}

// Registers `citation` as a node type the mdast/hast toolchain knows about — otherwise
// neither `parent.children.splice` below nor the `handlers` map passed to
// `remarkRehypeOptions` in Answer.tsx would type-check, since both are typed against a
// closed union of node names. This is the documented way to add a custom node type; see
// the `PhrasingContentMap` doc comment in `@types/mdast`.
declare module "mdast" {
  interface RootContentMap {
    citation: CitationNode;
  }
  interface PhrasingContentMap {
    citation: CitationNode;
  }
}

// `citationHandler` below emits a hast element with this tag name, and react-markdown's
// `components` prop is keyed by `JSX.IntrinsicElements` — without this, "citation" isn't
// a tag TypeScript recognises, the same way it wouldn't recognise a made-up HTML tag.
// React 19 exports its `JSX` namespace from the "react" module itself rather than as an
// ambient global, so the augmentation has to target that module, not `declare global`.
declare module "react" {
  namespace JSX {
    interface IntrinsicElements {
      citation: HTMLAttributes<HTMLElement>;
    }
  }
}

/**
 * Splits `[1]` / `[2, 3]` citation markers out of text nodes into their own mdast nodes.
 *
 * Markdown text nodes hold citation brackets as plain characters, same as any other
 * word. Without this, they render as literal "[1]" — this is what turns them back into
 * something `Answer.tsx` can render as a clickable control, the same way the plain-text
 * renderer it replaces did with a string-based regex split.
 *
 * Runs after `remark-gfm` in the plugin pipeline, so it only ever sees prose text, never
 * link/autolink syntax gfm may have already claimed.
 */
export function remarkCitations() {
  return (tree: Root) => {
    visit(tree, "text", (node: Text, index, parent: Parent | undefined) => {
      if (!parent || index === undefined) return;
      const matches = [...node.value.matchAll(CITATION)];
      if (matches.length === 0) return;

      const replacement: (Text | CitationNode)[] = [];
      let cursor = 0;
      for (const match of matches) {
        const at = match.index ?? 0;
        if (at > cursor) {
          replacement.push({ type: "text", value: node.value.slice(cursor, at) });
        }
        const numbers = match[1].split(",").map((piece) => Number(piece.trim()));
        replacement.push({ type: "citation", numbers });
        cursor = at + match[0].length;
      }
      if (cursor < node.value.length) {
        replacement.push({ type: "text", value: node.value.slice(cursor) });
      }

      parent.children.splice(index, 1, ...replacement);
      // Resume after the inserted nodes rather than re-visiting them.
      return index + replacement.length;
    });
  };
}

/**
 * Turns a `citation` mdast node into a hast element `remark-rehype` (and so
 * `react-markdown`) knows how to hand to a component override.
 *
 * The numbers travel as the element's single text child rather than a hast property,
 * so nothing depends on how an unknown property name happens to survive the hast
 * property schema — `props.children` is unambiguous.
 */
export function citationHandler(_state: unknown, node: CitationNode) {
  return {
    type: "element" as const,
    tagName: "citation",
    properties: {},
    children: [{ type: "text" as const, value: node.numbers.join(",") }],
  };
}
