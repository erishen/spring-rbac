# 多阶段构建 spring-rbac 前端（Next.js + React）
# 构建期把 BACKEND_URL 默认值烤进 rewrite 目标（容器网络内网关即 gateway-service:4100），
# 运行期仍可经环境变量覆盖，无需改动 next.config.mjs。
FROM node:20-alpine AS builder
WORKDIR /app
ARG BACKEND_URL=http://gateway-service:4100
ENV BACKEND_URL=$BACKEND_URL
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM node:20-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=3000
ENV BACKEND_URL=http://gateway-service:4100
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/.next ./.next
COPY --from=builder /app/package.json ./package.json
COPY --from=builder /app/next.config.mjs ./next.config.mjs
EXPOSE 3000
CMD ["npm", "run", "start"]
