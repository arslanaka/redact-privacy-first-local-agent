import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { AppHeader } from "@/components/app-header";
import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import "./globals.css";

const geistSans = Geist({ variable: "--font-sans", subsets: ["latin"] });
const geistMono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });

export const metadata: Metadata = {
  title: "Local Privacy Agent",
  description: "Detect and review sensitive information before sharing documents.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}>
      <body className="flex min-h-full flex-col bg-zinc-50">
        <TooltipProvider delay={200}>
          <AppHeader />
          <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-8">{children}</main>
          <footer className="border-t py-4 text-center text-xs text-muted-foreground">
            Local-first experiment. Not a certified anonymisation tool. Every redaction requires human review.
          </footer>
        </TooltipProvider>
        <Toaster position="bottom-right" />
      </body>
    </html>
  );
}
