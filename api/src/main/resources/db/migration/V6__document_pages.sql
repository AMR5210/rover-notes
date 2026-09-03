-- Where each page of a paginated document sits in its extracted text.
--
-- A document is indexed as one flat string and a citation names a character span into
-- it. That is the right unit for retrieval and the wrong one for a reader: checking a
-- citation against a PDF means turning to a page, and "characters 51,200 to 51,900" is
-- not a page. These rows are the translation, and they are the only place in the system
-- that knows a document had pages at all.
--
-- A table rather than a key in `documents.metadata`, because the lookup is a range
-- containment — which page holds this offset — and that is a query the planner can do
-- against an index. The same question against a JSON array means reading the array into
-- the application for every citation on every answer.
create table document_pages (
    document_id uuid not null references documents (id) on delete cascade,

    -- The number printed on the page, which is what a citation quotes. Sequential from
    -- one, including pages that hold no extractable text: a blank page that was skipped
    -- would make every number after it disagree with the document a reader is holding.
    number      int  not null,

    -- Half-open, matching the spans on chunks: char_start is inside the page and
    -- char_end is the first character of the next one. Adjacent pages therefore share a
    -- boundary value and no offset belongs to two pages.
    char_start  int  not null,
    char_end    int  not null,

    -- How many tables were extracted from this page. Carried because a table is the
    -- part of a PDF most often asked about and least reliably parsed, so a page whose
    -- answer looks wrong can be checked against whether a table was found there.
    tables      int  not null default 0,

    primary key (document_id, number),

    constraint document_pages_span_check check (char_start >= 0 and char_end >= char_start)
);

-- Resolving a citation: given a document and an offset, which page. Leading with
-- char_start lets the planner range-scan rather than read every page of the document.
create index document_pages_span_idx on document_pages (document_id, char_start, char_end);
