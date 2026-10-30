import type { Metadata } from 'next';

import './globals.css';

export const metadata: Metadata = {
  title: 'Saathi — pilot storefront',
  description: 'The customer-facing storefront for the Saathi local delivery pilot.',
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
