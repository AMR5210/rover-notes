# The MCP server

The knowledge base is exposed over the Model Context Protocol so any MCP-capable agent
can query it as a tool.

The tool surface is intentionally small and composable rather than one endpoint that
tries to answer everything: search returns ranked snippets with stable IDs,
get_document returns full content or a single span, and list_documents browses metadata
without incurring embedding cost.

Results are compact snippets rather than whole documents. Agent context is the scarce
resource, and an agent that can issue a second call does not need the first one to be
exhaustive.

This is what turns the project from a website into infrastructure other tools call.
