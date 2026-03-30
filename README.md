# cdata-embedded-drivers

A Claude Code plugin marketplace for CData embedded / OEM driver tools.

## Available plugins

### cdata-changelog-review

Review CData OEM connector changelogs and discover available releases.

| Tool | Description |
|------|-------------|
| `list_releases` | Discover available CData OEM releases (e.g. 2025 U1, 2025 U2), newest first |
| `get_changelog` | View changelog entries for a connector after a specific build number or release |

**Example prompts:**

- "What CData releases are available?"
- "Show me the MongoDB JDBC changelog since build 9000"
- "What changed in the Salesforce ADO connector since U1?"

**Supported editions:** JDBC, ADO .NET FRAMEWORK, ADO .NET STANDARD, ODBC UNIX, ODBC WINDOWS, PYTHON MAC, PYTHON UNIX, PYTHON WINDOWS

## Prerequisites

- Java 17+ (must be on your system PATH)

## Install via Claude Code

```
/plugin marketplace add YOUR_USERNAME/cdata-embedded-drivers
/plugin install cdata-changelog-review@cdata-embedded-drivers
```

To update later:

```
/plugin marketplace update
```

## Manual setup

If you prefer not to use the plugin marketplace, you can configure individual MCP servers directly.

### Download the JAR

Download `cdata-changelog-review-mcp.jar` from the [latest release](../../releases/latest), or build from source:

```bash
git clone https://github.com/YOUR_USERNAME/cdata-embedded-drivers.git
cd cdata-embedded-drivers/plugins/cdata-changelog-review
mvn package
```

The fat JAR is produced at `target/cdata-changelog-review-mcp.jar`.

### Client configuration

This server communicates over **stdio** and works with any MCP client that supports the stdio transport.

> **Important:** MCP clients spawn the server process directly without a shell, so environment variables like `JAVA_HOME` and shell `PATH` additions may not be available. Use the **full absolute path** to your `java` binary if needed.
>
> - **Windows example:** `C:\\Program Files\\Java\\jdk-17\\bin\\java.exe`
> - **macOS example:** `/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/bin/java`
> - **Linux example:** `/usr/lib/jvm/java-17/bin/java`

#### Claude Desktop

Add to your config file:

- **Windows:** `%APPDATA%\Claude\claude_desktop_config.json`
- **macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`

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

#### Claude Code (CLI)

```bash
claude mcp add cdata-changelog-review -- /full/path/to/java -jar /absolute/path/to/cdata-changelog-review-mcp.jar
```

#### Cursor

Add to `.cursor/mcp.json` in your project root or `~/.cursor/mcp.json` globally:

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

#### Windsurf

Add to `~/.codeium/windsurf/mcp_config.json`:

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

#### Other MCP clients

Any client that supports stdio-based MCP servers can use this:

```
/full/path/to/java -jar /absolute/path/to/cdata-changelog-review-mcp.jar
```

The server reads JSON-RPC from stdin and writes responses to stdout.
