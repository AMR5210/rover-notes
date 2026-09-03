# PDF parsing

Parsing runs in the Python service, which keeps native document libraries out of the JVM
process and lets the parser be restarted independently.

Text extraction uses PyMuPDF, which returns text blocks with page numbers and bounding
boxes. Page number and block order are carried into chunk metadata, so a citation can
name a page as well as a character range. Uploads are capped at 50 MB and 500 pages, and
a file over either limit is rejected at the API rather than after it reaches a worker.

A page yielding fewer than 100 characters of extractable text is treated as scanned and
routed to Tesseract OCR at 300 DPI. OCR costs roughly 2 seconds per page against about 20
milliseconds for native extraction, which is why the threshold exists rather than running
OCR everywhere.

Tables are extracted separately and serialised as pipe-delimited rows, because a table
flattened into prose loses which column a value belonged to. Formulas and figures are not
extracted; the surrounding caption text is indexed in their place.
