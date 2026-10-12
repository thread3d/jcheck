// Independent interoperability check for jcheck's MCP server, using the official
// @modelcontextprotocol/sdk client rather than jcheck's own hand-written client.
//
// This is NOT part of tests/run.sh: jcheck's own suite is deliberately dependency-free and
// offline. Run this by hand when you want to confirm interoperability with a real client
// implementation:
//
//   cd tests/mcp-sdk-verify
//   npm install
//   node verify.mjs /path/to/jcheck /path/to/a/java/project
//
// Exits non-zero if any check fails.

import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { ResourceUpdatedNotificationSchema } from "@modelcontextprotocol/sdk/types.js";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const jcheck = process.argv[2];
const projectArg = process.argv[3];
if (!jcheck) {
  console.error("usage: node verify.mjs <jcheck-launcher> [project-root]");
  process.exit(2);
}

const failures = [];
function check(name, cond, detail) {
  if (cond) {
    console.log("ok   " + name);
  } else {
    console.log("FAIL " + name + (detail ? "  -- " + detail : ""));
    failures.push(name);
  }
}

// Build a throwaway project if one was not supplied, so the script is self-contained.
let project = projectArg;
let tempRoot = null;
if (!project) {
  tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), "jcheck-sdk-verify-"));
  const dir = path.join(tempRoot, "src/main/java/demo");
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(
    path.join(dir, "Cart.java"),
    [
      "package demo;",
      "",
      "import java.util.List;",
      "",
      "public final class Cart {",
      "    public static int total(List<Integer> parts) {",
      '        String label = "total";',
      "        int sum = 0;",
      "        for (Integer part : parts) {",
      "            sum += part;",
      "        }",
      "        return sum + label.length();",
      "    }",
      "}",
      "",
    ].join("\n")
  );
  project = tempRoot;
}

const transport = new StdioClientTransport({
  command: jcheck,
  args: ["mcp", "--root", project, "--watch-interval-ms", "50"],
  stderr: "pipe",
});

let serverStderr = "";
if (transport.stderr) {
  transport.stderr.on("data", (d) => {
    serverStderr += d.toString();
  });
}

const client = new Client({ name: "jcheck-sdk-verify", version: "1.0.0" });

try {
  await client.connect(transport);
  check("initialize handshake completes", true);

  const instructions = client.getInstructions() || "";
  check("instructions point at the capabilities resource",
    instructions.includes("jcheck://capabilities"), instructions.slice(0, 80));

  const capabilities = client.getServerCapabilities();
  check("declares resources.subscribe",
    capabilities?.resources?.subscribe === true, JSON.stringify(capabilities));

  const list = await client.listResources();
  const uris = list.resources.map((r) => r.uri);
  check("lists 5 resources", list.resources.length === 5, JSON.stringify(uris));
  check("lists jcheck://capabilities", uris.includes("jcheck://capabilities"));
  check("every resource has a mimeType",
    list.resources.every((r) => typeof r.mimeType === "string"));

  const templates = await client.listResourceTemplates();
  const templateUris = templates.resourceTemplates.map((t) => t.uriTemplate);
  check("lists 2 resource templates", templates.resourceTemplates.length === 2,
    JSON.stringify(templateUris));
  check("template for docs", templateUris.includes("jcheck://docs/{code}"));

  const capsRes = await client.readResource({ uri: "jcheck://capabilities" });
  const capsText = capsRes.contents[0].text;
  check("capabilities is markdown", capsRes.contents[0].mimeType === "text/markdown");
  check("capabilities states the T3 gap",
    capsText.includes("Error Prone and NullAway plugin jars are not attached"));
  check("capabilities lists catalogued-but-unimplemented",
    capsText.includes("Catalogued but not implemented"));
  check("capabilities states where the guarantee ends",
    capsText.includes("Where the guarantee ends"));

  const diag = await client.readResource({ uri: "jcheck://diagnostics" });
  const diagText = diag.contents[0].text;
  check("diagnostics reports the J0706 finding", diagText.includes("J0706"), diagText.slice(0, 120));
  check("diagnostics reports the tier reached", diagText.includes("analysis reached"));

  const jsonRes = await client.readResource({ uri: "jcheck://diagnostics.json" });
  check("ndjson mime type", jsonRes.contents[0].mimeType === "application/x-ndjson",
    jsonRes.contents[0].mimeType);
  const lines = jsonRes.contents[0].text.trim().split("\n");
  let allJson = true;
  for (const line of lines) {
    try {
      JSON.parse(line);
    } catch {
      allJson = false;
    }
  }
  check("every ndjson line parses", allJson);
  check("ndjson ends with a summary", lines[lines.length - 1].includes('"summary"'));

  const docs = await client.readResource({ uri: "jcheck://docs/J0706" });
  check("docs page for J0706", docs.contents[0].text.includes("LocalCanBeFinal"));

  const catalog = await client.readResource({ uri: "jcheck://catalog/J0201" });
  check("catalog entry for J0201",
    catalog.contents[0].text.includes("compiler.err.not.exhaustive.statement"));

  let notFound = null;
  try {
    await client.readResource({ uri: "jcheck://nope" });
  } catch (e) {
    notFound = e;
  }
  check("unknown resource is an error", notFound !== null, String(notFound));

  // Subscription: the server should notify when the source tree changes.
  let updatedUri = null;
  client.setNotificationHandler(ResourceUpdatedNotificationSchema, (n) => {
    updatedUri = n.params.uri;
  });
  await client.subscribeResource({ uri: "jcheck://diagnostics" });
  check("subscribe accepted", true);

  await new Promise((r) => setTimeout(r, 700)); // let the watcher take its baseline
  fs.appendFileSync(path.join(project, "src/main/java/demo/Cart.java"), "\n// verify trigger\n");

  const deadline = Date.now() + 15000;
  while (!updatedUri && Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 100));
  }
  check("received notifications/resources/updated",
    updatedUri === "jcheck://diagnostics", String(updatedUri));

  // The changed tree must be reflected on the next read.
  const after = await client.readResource({ uri: "jcheck://diagnostics" });
  check("re-read reflects the change", after.contents[0].text.includes("J0706"));

  await client.unsubscribeResource({ uri: "jcheck://diagnostics" });
  check("unsubscribe accepted", true);
} catch (e) {
  check("no unexpected exception", false, String(e && e.stack ? e.stack : e));
} finally {
  try {
    await client.close();
  } catch {
    // already gone
  }
  if (tempRoot) {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
}

check("server logged only to stderr", serverStderr.includes("serving"), serverStderr.slice(0, 200));

console.log("");
if (failures.length > 0) {
  console.log(`${failures.length} check(s) failed`);
  process.exit(1);
}
console.log("all checks passed");
