# Agent Orchestrator MCP for Claude Code

Node.js 20+ package for the optional local supervisor. The existing Claude web connector continues to use the remote `/mcp` endpoint directly.

## Install

```sh
npm install -g https://d3tlsuzwwbes8y.cloudfront.net/downloads/workspace-mcp.tgz
workspace-mcp configure https://d3tlsuzwwbes8y.cloudfront.net/mcp
workspace-mcp install
```

`configure` prompts for a personal MCP credential without echoing it. Obtain one in **Equipo y agentes → Conexiones MCP**. The credential is stored in `~/.agent-orchestrator/config.json` for the operating-system user. You can instead set `WORKSPACE_MCP_URL` and `WORKSPACE_MCP_TOKEN` in the stdio process environment.

The `install` command uses `claude mcp add` to register one user-scoped stdio server. Starting Claude Code then starts a detached local daemon automatically. `connect_agent` binds the returned agent and workspace IDs to the current directory. For an agent already connected elsewhere, run `workspace-mcp bind <agentId> <workspaceId> <directory>`.

Use `workspace-mcp status` and `workspace-mcp stop` for local diagnostics and shutdown. Reopen Claude Code after a machine restart to start the daemon again. To replace a saved credential, run `configure` again followed by `stop`; the next Claude Code session starts with the new credential.

The daemon uses MCP `subscriptions/listen` to watch each bound agent inbox. After every connection loss it rereads the durable resource. Existing messages are baselined on first binding; later messages are deduplicated locally. Only review, help, coordination, and task handoff messages wake Claude. The daemon does not bypass Claude Code permissions. A failed wake is logged to `~/.agent-orchestrator/daemon.log` and is not automatically replayed; the message remains in the cloud inbox for manual handling.

The daemon listens on a random `127.0.0.1` port and requires a random secret stored in `~/.agent-orchestrator/daemon.json`. The remote credential never goes into MCP stdio output. Protect the user profile directory and revoke the credential in the web panel if the device is lost.
