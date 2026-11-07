# Tradex email MCP server: Streamable HTTP on :3001/mcp, health on :3001/health
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-fund --no-audit
COPY tsconfig.json ./
COPY src ./src
RUN npm run build && npm prune --omit=dev

FROM node:22-alpine
ENV NODE_ENV=production MCP_PORT=3001 MCP_PATH=/mcp
WORKDIR /app
COPY --from=build /app/package.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/build ./build
USER node
EXPOSE 3001
HEALTHCHECK --interval=10s --timeout=5s --retries=6 --start-period=5s \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.MCP_PORT||3001)+'/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "build/index.js"]
