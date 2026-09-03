# Connecting a client to the MCP server

The server is at `/mcp` over streamable HTTP and exposes `search`, `get_document` and
`list_documents`. `claude-code.json` is a working client configuration for a local run:

```bash
claude --mcp-config mcp/claude-code.json
```

For a deployed instance, change the URL and nothing else:

```json
{
  "mcpServers": {
    "rover-notes": {
      "type": "http",
      "url": "https://your-host/mcp"
    }
  }
}
```

No token goes in the file. A client that has none is refused with a `WWW-Authenticate`
header naming a metadata document, and follows it from there:

    POST /mcp                                   401, WWW-Authenticate: Bearer
                                                     resource_metadata="…"
    GET  /.well-known/oauth-protected-resource  names the authorization server
    GET  /.well-known/oauth-authorization-server names the registration, authorization
                                                 and token endpoints
    POST /oauth2/register                        the client registers itself, no
                                                 credential required
    GET  /oauth2/authorize                       the person signs in and approves
    POST /oauth2/token                           the client exchanges its code

Each step is served because the one before it points at it, which is what lets a URL be
the whole configuration. `McpDiscoveryTest` walks the chain in this order, and
`McpAgentTokenTest` finishes it: register, sign in, approve, exchange, then call `/mcp`
with the result, which answers 200 where the same call without a token answers 401.

Registration grants a deliberately narrow client, and it is not by itself access. The
client is public, PKCE is required, authorization code is the only grant, and its access
token lasts fifteen minutes. Scopes are assigned by the server: Spring Authorization Server
refuses a registration that names any. The client also **requires consent**. The web
interface skips the approval screen because it is this project's own front end, and
anything that registered itself is not, so a registration reaches nothing until a person
signs in and approves it.

Anyone who can reach the endpoint can create a client row. An unused client is inert, and
the registration path is covered by the request limit of ten attempts a minute, keyed by
client address, since callers there have no account yet.

## The acceptance check

`make mcp-check` is the end-to-end check for this module: a real client connects, calls
the tools, and answers a question only this corpus can answer.

It seeds a document containing a generated identifier, asks a question whose answer
depends on it, and then deletes the document again. The identifier is generated per run
so a correct answer cannot come from the model's own knowledge, from a cache, or from an
earlier run, since the tools are the only path to it. The check asserts three things: that the
tools were actually called, that no call returned an error, and that the answer contains
the seeded value.

The document is removed on every exit path, including failure. The corpus this runs
against is also the eval corpus, and the harness refuses to score a corpus whose document
count is not the one it seeded.

Requires a running API (`make up`, `make api`) and the `claude` CLI.
