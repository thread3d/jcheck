# jcheck MCP interoperability check

Confirms that jcheck's MCP server works with a **real** MCP client — the official
[`@modelcontextprotocol/sdk`](https://github.com/modelcontextprotocol/typescript-sdk) — rather
than only with jcheck's own hand-written client in `tests/java/jcheck/tests/McpTests.java`.

This is deliberately **not** part of `tests/run.sh`. jcheck's own test suite is dependency-free
and runs offline with nothing but a JDK; this check needs npm and the network, so it is opt-in.

```bash
cd tests/mcp-sdk-verify
npm install
node verify.mjs /path/to/jcheck/jcheck         # creates its own throwaway project
node verify.mjs /path/to/jcheck/jcheck /path/to/a/java/project
```

Exits non-zero if any check fails. It verifies the initialize handshake, capability
declaration, resource and template listing, every resource's content, the `-32002` error for an
unknown resource, the `resources/subscribe` → `notifications/resources/updated` flow after a
source change, and that the server logs only to stderr.

`node_modules/` is not committed; run `npm install` first.
