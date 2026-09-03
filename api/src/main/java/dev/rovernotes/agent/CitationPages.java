package dev.rovernotes.agent;

import java.util.List;
import java.util.Map;

import dev.rovernotes.notes.DocumentPages;
import org.springframework.stereotype.Component;

/**
 * Fills in the page each citation points at, where the document has pages.
 *
 * <p>A citation carries a character span, which is the right unit for highlighting a
 * passage in a viewer and useless to a reader holding a PDF. This is the last step that
 * turns the answer into something checkable: "page 34" rather than "characters 51,200 to
 * 51,900".
 *
 * <p>One lookup for the whole answer rather than one per citation. An answer carries up
 * to ten sources and most of them usually come from the same document, so the per-citation
 * form repeats the same range scan several times over.
 *
 * <p>Both answer paths pass through here — the single retrieval pass and the agent loop —
 * because a citation should mean the same thing whichever produced it. The two build their
 * citation lists separately, so this is the shared step rather than a duplicated one.
 */
@Component
class CitationPages {

    private final DocumentPages pages;

    CitationPages(DocumentPages pages) {
        this.pages = pages;
    }

    /**
     * The same citations, each carrying its page number where one exists.
     *
     * <p>A citation into a typed note keeps a null page, which is not a failure: most
     * documents in a corpus like this were never paginated, and a page number invented
     * for one would be worse than its absence.
     */
    List<AnswerService.Citation> resolve(List<AnswerService.Citation> citations) {
        if (citations.isEmpty()) {
            return citations;
        }

        Map<java.util.UUID, Integer> found = pages.pagesFor(citations.stream()
                .map(c -> new DocumentPages.Span(c.chunkId(), c.documentId(), c.charStart()))
                .toList());

        if (found.isEmpty()) {
            return citations;
        }
        return citations.stream()
                .map(c -> c.withPage(found.get(c.chunkId())))
                .toList();
    }
}
