# Build and run the Next.js frontend.
#
# The build needs the full dependency tree; the runtime needs the standalone bundle Next
# emits, which carries only the modules actually reached. That is the difference between
# an image of a few hundred megabytes and one over a gigabyte.
FROM node:22-slim AS build
WORKDIR /build

COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
# Rewrites resolve at request time, so the API address is a runtime concern and this
# build needs no value for it.
RUN npm run build

FROM node:22-slim AS runtime
WORKDIR /app
ENV NODE_ENV=production

# `node` already exists in this image as an unprivileged user.
COPY --from=build --chown=node:node /build/.next/standalone ./
COPY --from=build --chown=node:node /build/.next/static ./.next/static
COPY --from=build --chown=node:node /build/public ./public
USER node

EXPOSE 3000
ENV PORT=3000 HOSTNAME=0.0.0.0

CMD ["node", "server.js"]
