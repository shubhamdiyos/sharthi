import type { NextConfig } from 'next';

// `standalone` is what infra/docker/web.Dockerfile copies into the runtime
// stage: server.js plus the traced dependencies, and no node_modules tree.
const nextConfig: NextConfig = {
  output: 'standalone',
  reactStrictMode: true,
};

export default nextConfig;
