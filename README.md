# cdata-embedded-drivers

MCP tools for CData Embedded / OEM driver management.

## Available plugins

### cdata-changelog-review

Review CData connector changelogs and discover available releases.

| Tool | Description                                                                              |
|------|------------------------------------------------------------------------------------------|
| `list_releases` | List CData connector releases (e.g. 2025 U1, 2025 U2)                                    |
| `get_changelog` | Retrieve changelog entries for a specific connector, filtered by release or build number |

**Example prompts:**

- "What CData connector releases are available?"
- "Retrieve all the MongoDB changes since the second release of 2025"
- "What changed in the Salesforce ADO connector since build 25.0.9000?"

**Supported editions:** JDBC, ADO .NET FRAMEWORK, ADO .NET STANDARD, ODBC UNIX, ODBC WINDOWS, PYTHON MAC, PYTHON UNIX, PYTHON WINDOWS

## Prerequisites

- Java 17+

## Setup

#### Claude Code

No download required — install directly via the plugin marketplace:

```
/plugin marketplace add RyanNeeves/embedded-drivers-beta
/plugin install cdata-changelog-review@cdata-embedded-drivers
```

#### Claude Desktop, Cursor, and other MCP clients

Download `cdata-changelog-review-mcp.jar` from the [latest release](../../releases/latest), or build from source:

```bash
git clone https://github.com/RyanNeeves/embedded-drivers-beta.git
cd cdata-embedded-drivers/plugins/cdata-changelog-review
mvn package
```

The fat JAR is produced at `target/cdata-changelog-review-mcp.jar`.

This server communicates over **stdio** and works with any MCP client that supports the stdio transport.

> **Important:** MCP clients spawn the server process directly without a shell, so environment variables like `JAVA_HOME` and shell `PATH` additions may not be available. Use the **full absolute path** to your `java` binary if needed.
>
> - **Windows example:** `C:\\Program Files\\Java\\jdk-17\\bin\\java.exe`
> - **macOS example:** `/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/bin/java`
> - **Linux example:** `/usr/lib/jvm/java-17/bin/java`

**Claude Desktop** — add to `%APPDATA%\Claude\claude_desktop_config.json` (Windows) or `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS):

```json
{
  "mcpServers": {
    "cdata-changelog-review": {
      "command": "/full/path/to/java",
      "args": ["-jar", "/absolute/path/to/cdata-changelog-review-mcp.jar"]
    }
  }
}
```

**Cursor** — add to `.cursor/mcp.json` in your project root or `~/.cursor/mcp.json` globally:

```json
{
  "mcpServers": {
    "cdata-changelog-review": {
      "command": "/full/path/to/java",
      "args": ["-jar", "/absolute/path/to/cdata-changelog-review-mcp.jar"]
    }
  }
}
```

**Other MCP clients** — run the server directly:

```
/full/path/to/java -jar /absolute/path/to/cdata-changelog-review-mcp.jar
```

## Uninstall

#### Claude Code

```
/plugin uninstall cdata-changelog-review@cdata-embedded-drivers
/plugin marketplace remove cdata-embedded-drivers
```

If the plugin doesn't fully uninstall, clear the cached files and plugin registry manually:

```bash
# macOS/Linux
rm -rf ~/.claude/plugins/cache/cdata-embedded-drivers
rm -rf ~/.claude/plugins/marketplaces/cdata-embedded-drivers

# Windows (PowerShell)
Remove-Item -Recurse -Force "$env:USERPROFILE\.claude\plugins\cache\cdata-embedded-drivers"
Remove-Item -Recurse -Force "$env:USERPROFILE\.claude\plugins\marketplaces\cdata-embedded-drivers"
```

Then remove any leftover entries from `~/.claude/plugins/installed_plugins.json` and `~/.claude/plugins/known_marketplaces.json`.

#### Claude Desktop, Cursor, and other MCP clients

Remove the `cdata-changelog-review` entry from your config file and delete the JAR.
