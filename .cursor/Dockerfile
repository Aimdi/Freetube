# Base image for the FreeTube Cloud Agent development environment.
# FreeTube's tooling (ESLint 10 flat config, Stylelint, webpack build) requires
# Node.js 24, matching the version used in the project's CI workflows.
FROM node:24-bookworm-slim

# System packages needed to install dependencies and run the tooling:
# - git & ca-certificates: dependency resolution (some deps install from git) and TLS
# - python3, make, g++: node-gyp toolchain for native modules
# - curl: general fetch utility used during development
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        git \
        ca-certificates \
        curl \
        python3 \
        make \
        g++ \
    && rm -rf /var/lib/apt/lists/*

# Pin the pnpm version used by the project's lockfile via corepack.
RUN corepack enable && corepack prepare pnpm@10.33.3 --activate

# The node image ships with a uid/gid 1000 "node" user used by the agent.
USER node
