# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM node:22-alpine AS builder

WORKDIR /app

# Cache dependency layer — only re-run if package files change
COPY package.json package-lock.json ./
RUN npm ci --prefer-offline

# Copy source files needed for the build
COPY index.html .
COPY postcss.config.js .
COPY vite.config.js .
COPY jsconfig.json .
COPY src ./src
COPY public ./public

# Cache-bust arg — pass current git SHA so source changes always trigger a fresh
# build without invalidating the npm ci layer above.
ARG CACHEBUST=unknown
RUN echo "build: $CACHEBUST"

# VITE_API_URL controls the backend endpoint baked into the JS bundle.
# Leave blank to use nginx proxy (recommended for docker-compose deployments).
# Set to a full URL (e.g. https://api.mycompany.com) for standalone frontend.
ARG VITE_API_URL=""
ENV VITE_API_URL=$VITE_API_URL

RUN npm run build:production

# ── Stage 2: Serve ────────────────────────────────────────────────────────────
FROM nginx:1.31-alpine AS runtime

# Copy built assets
COPY --from=builder /app/dist /usr/share/nginx/html

# Copy nginx config for SPA routing + API proxy
COPY docker/nginx/default.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
