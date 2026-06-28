export const metadata = {
  title: "카메라 프린트 기록",
  description: "카메라 프린트 앱 인쇄 기록 웹 뷰어",
};

export const viewport = {
  width: "device-width",
  initialScale: 1,
  maximumScale: 1,
};

export default function RootLayout({ children }) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
