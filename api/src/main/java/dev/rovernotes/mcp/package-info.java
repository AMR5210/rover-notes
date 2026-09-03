/**
 * MCP server: exposes the knowledge base as tools any MCP-capable agent can call.
 *
 * <p>The tool surface is intentionally small and composable - search, fetch, browse -
 * so an agent can combine calls and refine its own path to an answer. Results are
 * returned as compact snippets with stable IDs, since agent context is limited.

 */
@org.springframework.modulith.ApplicationModule(displayName = "MCP")
package dev.rovernotes.mcp;
