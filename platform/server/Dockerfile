FROM node:24.14.0-bookworm-slim
ENV NODE_ENV=production PORT=8080 DATA_DIR=/data WEB_ROOT=/app/web
WORKDIR /app/server
COPY package.json ./
COPY src ./src
RUN mkdir -p /data /app/web && chown -R node:node /data /app
USER node
EXPOSE 8080
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 CMD node -e "fetch('http://127.0.0.1:'+process.env.PORT+'/api/v1/health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"
CMD ["node", "src/index.mjs"]
