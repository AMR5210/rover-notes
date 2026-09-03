"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const LINKS = [
  { href: "/ask", label: "Ask" },
  { href: "/search", label: "Search" },
  { href: "/notes", label: "Library" },
  { href: "/usage", label: "Usage" },
];

/** Marks the current section, which needs the pathname and so needs the client. */
export function Nav() {
  const pathname = usePathname();
  return (
    <nav>
      {LINKS.map((link) => (
        <Link
          key={link.href}
          href={link.href}
          className={pathname === link.href ? "current" : undefined}
          aria-current={pathname === link.href ? "page" : undefined}
        >
          {link.label}
        </Link>
      ))}
    </nav>
  );
}
