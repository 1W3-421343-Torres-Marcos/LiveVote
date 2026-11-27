# syntax=docker/dockerfile:1

FROM --platform=$BUILDPLATFORM node:22.12.0-alpine3.20 AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM nginx:1.27.3-alpine

LABEL org.opencontainers.image.title="livevote-frontend" \
      org.opencontainers.image.description="LiveVote Angular SPA and nginx reverse proxy" \
      org.opencontainers.image.source="https://github.com/1W3-421343-Torres-Marcos/LiveVote"

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/livevote-frontend/browser/ /usr/share/nginx/html/

EXPOSE 80
