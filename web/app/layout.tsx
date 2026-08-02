import "./globals.css";
import type { Metadata } from "next";
import { AuthProvider } from "@/lib/auth";

export const metadata: Metadata = {
  title: "spring-rbac 控制台",
  description: "Spring Cloud RBAC 微服务前端（Next.js + React）",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN">
      <body>
        <AuthProvider>{children}</AuthProvider>
      </body>
    </html>
  );
}
