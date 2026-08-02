/** @type {import('next').NextConfig} */
const BACKEND_URL = process.env.BACKEND_URL || "http://localhost:4100";

const nextConfig = {
  // BFF 风格：浏览器只与同源 /api 打交道，由 Next 服务端代理到 Spring Cloud Gateway。
  // 这样天然规避浏览器跨域，也契合「全栈 = BFF / Node 服务层」的边界（不让前端直连后端）。
  // 裸 jar / Compose 跑后端时网关在 4100；k3s 转发后在本机 41000（用 BACKEND_URL 覆盖）。
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${BACKEND_URL}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
