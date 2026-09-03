import type { Metadata } from "next";
import type { ReactNode } from "react";

import { AccountBadge } from "./components/Account";
import { Nav } from "./components/Nav";
import "./globals.css";

export const metadata: Metadata = {
  title: "Rover Notes",
  description: "A personal knowledge platform any AI agent can query as a tool.",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>
        <header className="masthead">
          <a className="brand" href="/">
            Rover Notes
          </a>
          <Nav />
          <AccountBadge />
        </header>
        <main>{children}</main>
      </body>
    </html>
  );
}
