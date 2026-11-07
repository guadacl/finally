"""Runs mcp_massive over Streamable HTTP reachable from the compose network.

mcp_massive builds its FastMCP server with the SDK defaults: it listens on 127.0.0.1 only and its DNS-rebinding
protection accepts only localhost Host headers. Inside a container the app must reach it as massive-mcp:8000, so
this launcher sets the bind address and port and allows that host name (protection stays on), then starts the
server exactly as the mcp_massive command would.
"""
import os

from mcp.server.transport_security import TransportSecuritySettings
from mcp_massive import main, server

host = os.environ.get("MCP_HOST", "0.0.0.0")
port = int(os.environ.get("MCP_PORT", "8000"))
allowed = [h.strip() for h in os.environ.get("MCP_ALLOWED_HOSTS", "massive-mcp").split(",") if h.strip()]

settings = server.mass_mcp.settings
settings.host = host
settings.port = port
settings.transport_security = TransportSecuritySettings(
    enable_dns_rebinding_protection=True,
    allowed_hosts=[f"{h}:*" for h in allowed] + ["127.0.0.1:*", "localhost:*"],
    allowed_origins=[f"http://{h}:*" for h in allowed] + ["http://127.0.0.1:*", "http://localhost:*"],
)

if __name__ == "__main__":
    main()
