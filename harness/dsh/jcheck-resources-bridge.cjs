#!/usr/bin/env node
"use strict";

/*
 * jcheck-resources-bridge
 *
 * jcheck's MCP server is deliberately resources-only; the DSH harness's MCP client
 * bridges tools only ("MCP resources and prompts are unsupported"). This bridge closes
 * that gap without changing jcheck: it speaks MCP to the harness on stdio and, per tool
 * call, proxies a `jcheck mcp --root <project>` server, exposing the two operations the
 * harness needs as tools:
 *
 *   mcp__jcheck__list_resources
 *   mcp__jcheck__read_resource
 *
 * The bridge is a harness adapter, not part of jcheck: jcheck keeps its CLI-only
 * interface and its resources-only MCP server, and any MCP host that reads resources can
 * keep using `jcheck mcp` directly.
 *
 * Configuration (environment):
 *   JCHECK_BIN          jcheck launcher (default below points at this checkout)
 *   JCHECK_BRIDGE_ROOT  default project root when a tool call omits `root`
 *   JCHECK_SDK_ROOT     the dsh installation whose bundled MCP SDK is reused
 *
 * stdout is the MCP protocol channel: log only to stderr.
 */

const fs = require("node:fs");
const path = require("node:path");
const { createRequire } = require("node:module");

const JCHECK_BIN = process.env.JCHECK_BIN
  || "/Users/threaded/projects/JCheck/jcheck/jcheck";
const DEFAULT_ROOT = process.env.JCHECK_BRIDGE_ROOT
  || "/Users/threaded/projects/JCheck/jcheck";
const SDK_ROOT = process.env.JCHECK_SDK_ROOT
  || "/Users/threaded/.npm-packages/lib/node_modules/@deepseek-ai/dsh";

function log(message) {
  process.stderr.write(`jcheck-bridge: ${message}\n`);
}

function sdkRequire(packageName) {
  // Reuse the SDK that the harness itself uses, so the protocol version and framing
  // always match the client on the other end.
  const requireFromDsh = createRequire(path.join(SDK_ROOT, "package.json"));
  return requireFromDsh(packageName);
}

const { Server } = sdkRequire("@modelcontextprotocol/sdk/server/index.js");
const { StdioServerTransport } = sdkRequire("@modelcontextprotocol/sdk/server/stdio.js");
const { Client } = sdkRequire("@modelcontextprotocol/sdk/client/index.js");
const { StdioClientTransport } = sdkRequire("@modelcontextprotocol/sdk/client/stdio.js");
const { ListToolsRequestSchema, CallToolRequestSchema } =
  sdkRequire("@modelcontextprotocol/sdk/types.js");

const BRIDGE_VERSION = "0.1.0";

/** root -> Promise<Client>; a warm jcheck server keeps its analysis between calls. */
const clients = new Map();
/** root -> last tool-call time, so a long session cannot accumulate warm servers forever. */
const lastUsed = new Map();
const MAX_WARM_ROOTS = 8;

function resolveRoot(raw) {
  const root = raw && String(raw).trim() ? String(raw).trim() : DEFAULT_ROOT;
  return path.resolve(root);
}

async function connectClient(root) {
  if (!fs.existsSync(root) || !fs.statSync(root).isDirectory()) {
    throw new Error(`project root does not exist or is not a directory: ${root}`);
  }
  if (!fs.existsSync(JCHECK_BIN)) {
    throw new Error(`jcheck launcher not found: ${JCHECK_BIN} (set JCHECK_BIN)`);
  }
  const transport = new StdioClientTransport({
    command: JCHECK_BIN,
    args: ["mcp", "--root", root],
    env: { ...process.env },
    stderr: "inherit",
  });
  const client = new Client(
    { name: "jcheck-resources-bridge", version: BRIDGE_VERSION },
    { capabilities: {} },
  );
  await client.connect(transport);
  return client;
}

function clientFor(root) {
  let pending = clients.get(root);
  if (!pending) {
    pending = connectClient(root).catch((error) => {
      clients.delete(root);
      throw error;
    });
    clients.set(root, pending);
    lastUsed.set(root, Date.now());
  }
  return pending;
}

async function evictOldest() {
  while (clients.size > MAX_WARM_ROOTS) {
    let victim = null;
    let victimTime = Infinity;
    for (const root of clients.keys()) {
      const seen = lastUsed.get(root) || 0;
      if (seen < victimTime) {
        victimTime = seen;
        victim = root;
      }
    }
    if (!victim) {
      return;
    }
    await disposeClient(victim);
  }
}

async function disposeClient(root) {
  lastUsed.delete(root);
  const pending = clients.get(root);
  clients.delete(root);
  if (!pending) {
    return;
  }
  try {
    const client = await pending;
    await client.close();
  } catch {
    // A failed connection has nothing left to close.
  }
}

/** Runs one operation against jcheck, reconnecting once if a cached server went away. */
async function withClient(root, operation) {
  lastUsed.set(root, Date.now());
  try {
    return await operation(await clientFor(root));
  } catch (first) {
    await disposeClient(root);
    try {
      lastUsed.set(root, Date.now());
      return await operation(await clientFor(root));
    } catch (second) {
      throw second;
    }
  } finally {
    lastUsed.set(root, Date.now());
    await evictOldest();
  }
}

function textResult(text) {
  return { content: [{ type: "text", text }] };
}

function errorResult(error) {
  const message = error && error.message ? error.message : String(error);
  log(`tool failed: ${message}`);
  return { content: [{ type: "text", text: `jcheck bridge error: ${message}` }], isError: true };
}

function describeResources(result) {
  const resources = Array.isArray(result && result.resources) ? result.resources : [];
  const lines = resources.map((resource) => {
    const name = resource.name || "(unnamed)";
    const uri = resource.uri || "";
    const description = resource.description ? ` — ${resource.description}` : "";
    return `${uri}\t${name}${description}`;
  });
  return lines.length === 0
    ? "jcheck exposed no resources for this root."
    : lines.join("\n");
}

function readText(result) {
  const contents = Array.isArray(result && result.contents) ? result.contents : [];
  return contents
    .map((content) => {
      if (typeof content.text === "string") {
        return content.text;
      }
      if (content.blob) {
        return `[binary resource ${content.uri || ""}: ${content.mimeType || "unknown"} type]`;
      }
      return `[resource ${content.uri || ""}: unsupported content]`;
    })
    .join("\n\n");
}

const server = new Server(
  { name: "jcheck-resources", version: BRIDGE_VERSION },
  { capabilities: { tools: {} } },
);

server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: [
    {
      name: "list_resources",
      description:
        "List the jcheck MCP resources available for a Java project: capabilities, "
        + "diagnostics (agent format and JSONL), the catalog, explain pages and "
        + "suppressions. Pass the project root, or omit it to use the configured default.",
      inputSchema: {
        type: "object",
        properties: {
          root: {
            type: "string",
            description: "Absolute path of the Java project to analyse.",
          },
        },
        required: [],
        additionalProperties: false,
      },
    },
    {
      name: "read_resource",
      description:
        "Read one jcheck resource by URI for a Java project. Read jcheck://capabilities "
        + "first: it states which analysis tiers actually ran and where the guarantee "
        + "ends. Other URIs: jcheck://diagnostics, jcheck://diagnostics.json, "
        + "jcheck://catalog, jcheck://catalog/{code}, jcheck://docs/{code}, "
        + "jcheck://suppressions.",
      inputSchema: {
        type: "object",
        properties: {
          uri: {
            type: "string",
            description: "A jcheck resource URI, for example jcheck://diagnostics.",
          },
          root: {
            type: "string",
            description: "Absolute path of the Java project to analyse.",
          },
        },
        required: ["uri"],
        additionalProperties: false,
      },
    },
  ],
}));

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params || {};
  const root = resolveRoot(args && args.root);
  try {
    if (name === "list_resources") {
      const result = await withClient(root, (client) => client.listResources());
      return textResult(`root: ${root}\n\n${describeResources(result)}\n`);
    }
    if (name === "read_resource") {
      const uri = args && args.uri ? String(args.uri) : "";
      if (!uri) {
        return errorResult(new Error("read_resource needs a uri"));
      }
      const result = await withClient(root, (client) => client.readResource({ uri }));
      return textResult(readText(result));
    }
    return errorResult(new Error(`unknown tool: ${name}`));
  } catch (error) {
    return errorResult(error);
  }
});

async function shutdown() {
  await Promise.all([...clients.keys()].map((root) => disposeClient(root)));
  process.exit(0);
}

async function main() {
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
  const transport = new StdioServerTransport();
  await server.connect(transport);
  log(`ready: jcheck=${JCHECK_BIN} defaultRoot=${DEFAULT_ROOT} sdk=${SDK_ROOT}`);
}

main().catch((error) => {
  log(`fatal: ${error && error.stack ? error.stack : String(error)}`);
  process.exit(1);
});
