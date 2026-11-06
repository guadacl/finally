# Massive (formerly Polygon.io) MCP server, used by Tradex for the watchlist ticker search.
# Upstream: https://github.com/massive-com/mcp_massive (pinned release; Python >= 3.12).
FROM python:3.13-slim

ARG MCP_MASSIVE_VERSION=v0.10.0

# mcp_massive v0.10.0 imports mcp.server.fastmcp, which the mcp SDK 2.x removed: keep the SDK on 1.x.
RUN pip install --no-cache-dir \
        "https://github.com/massive-com/mcp_massive/archive/refs/tags/${MCP_MASSIVE_VERSION}.tar.gz" \
        "mcp>=1.27,<2" \
    && useradd --create-home --uid 1000 mcp

COPY serve.py /app/serve.py

USER mcp

# Streamable HTTP on 0.0.0.0:8000/mcp, reachable as massive-mcp:8000 (see serve.py).
ENV MCP_TRANSPORT=streamable-http \
    MCP_HOST=0.0.0.0 \
    MCP_PORT=8000 \
    MCP_ALLOWED_HOSTS=massive-mcp \
    PYTHONUNBUFFERED=1

EXPOSE 8000

CMD ["python", "/app/serve.py"]
