# jcheck as native tools in a tools-only MCP host

jcheck's MCP server is deliberately resources-only, and the DeepSeek Harness's MCP client
bridges **tools only** ("MCP resources and prompts are unsupported"). Connecting `jcheck mcp`
to that host succeeds and exposes nothing, because there are no tools to bridge.

This directory holds the adapter that closes the gap without changing jcheck. The bridge speaks
MCP to the host on stdio and, per tool call, proxies a warm `jcheck mcp --root <project>` server.
It exposes two tools:

| Tool | What it does |
| --- | --- |
| `list_resources` | Lists `jcheck://capabilities`, `jcheck://diagnostics`, `jcheck://catalog`, … for a project root |
| `read_resource` | Reads one resource by URI, e.g. `jcheck://capabilities` or `jcheck://diagnostics` |

The host namespaces them, so in DSH they appear as `mcp__jcheck__list_resources` and
`mcp__jcheck__read_resource`. jcheck itself keeps its CLI-only interface and its resources-only
MCP server; a host that reads resources can keep using `jcheck mcp` directly.

## Install (DeepSeek Harness)

Copy the adapter outside the checkout (the harness config outlives the repo) and register one
MCP client entry in the home-level patch, `$DSH_HOME/cordis.patch.yml`:

```bash
mkdir -p ~/.dsh/mcp
cp harness/dsh/jcheck-resources-bridge.cjs ~/.dsh/mcp/
```

```yaml
- insert:
    - id: jcheck-mcp
      name: "@deepseek-ai/dsh-mcp-client"
      config:
        serverName: jcheck
        transport: stdio
        command: /usr/local/bin/node
        args:
          - /Users/threaded/.dsh/mcp/jcheck-resources-bridge.cjs
        env:
          JCHECK_BIN: /Users/threaded/projects/JCheck/jcheck/jcheck
          JCHECK_BRIDGE_ROOT: /Users/threaded/projects/JCheck/jcheck
          JCHECK_CACHE_DIR: /Users/threaded/projects/JCheck/.jcheck-cache
```

A profile with `patchReload: live` (the shipped `web` profile) picks the entry up without a
restart; otherwise start a new harness session.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `JCHECK_BIN` | this checkout's `jcheck` launcher | The CLI the bridge proxies |
| `JCHECK_BRIDGE_ROOT` | this checkout | Project root used when a tool call omits `root` |
| `JCHECK_SDK_ROOT` | the DSH installation | Whose bundled `@modelcontextprotocol/sdk` the bridge reuses |
| `JCHECK_CACHE_DIR` | jcheck's own default | Analyzer cache; set it where `~/.cache/jcheck` is unavailable |

Reusing the harness's own SDK is deliberate: the bridge and the host then negotiate the same MCP
protocol versions instead of drifting with a second copy.

## Verify without the harness

The adapter is an ordinary MCP stdio server, so the official SDK client can drive it:

```bash
node -e '
const {createRequire}=require("node:module");
const r=createRequire("/Users/threaded/.npm-packages/lib/node_modules/@deepseek-ai/dsh/package.json");
const {Client}=r("@modelcontextprotocol/sdk/client/index.js");
const {StdioClientTransport}=r("@modelcontextprotocol/sdk/client/stdio.js");
(async()=>{
  const c=new Client({name:"verify",version:"0"},{capabilities:{}});
  await c.connect(new StdioClientTransport({command:"node",args:["'"$HOME"'/.dsh/mcp/jcheck-resources-bridge.cjs"],env:{...process.env}}));
  console.log((await c.listTools()).tools.map(t=>t.name));
  const r=await c.callTool({name:"read_resource",arguments:{uri:"jcheck://capabilities"}});
  console.log(r.content[0].text.split("\n").slice(0,4).join("\n"));
  await c.close();
})();'
```

## Boundaries

- The bridge is a harness adapter, not part of jcheck's build; the Java project still needs only a
  JDK. It is not installed by `build.sh`.
- Each project root keeps a warm `jcheck mcp` process (that is what makes repeated reads fast).
  At most eight roots stay warm; the least recently used is disposed.
- It reads resources only. It does not run `jcheck check` as a tool: diagnostics arrive through
  `jcheck://diagnostics`, and the CLI stays the single checking interface.
- `read_resource` reports a missing resource as a tool error (`isError: true`), never as empty
  success.
