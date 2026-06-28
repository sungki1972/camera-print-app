# 카메라 프린트 앱 + 웹 기록 뷰어

카메라로 찍은 사진을 A4로 자동 인쇄하는 안드로이드 앱(`com.echeil.cameraprint`)과,
그 인쇄 기록을 어디서나 볼 수 있는 웹 뷰어(`web/`).

## 구조
- `app/` — 안드로이드 앱 (Kotlin, Room, WorkManager, OkHttp)
  - 촬영 → 로컬 Room DB(`print_logs`)에 기록 → `PrintWorker`가 `barcode1.echeil.com/api/print/image`로 업로드해 인쇄
  - **기록탭**(`LogsActivity`)은 로컬 DB 표시
- `web/` — Next.js 14 (App Router) 웹 기록 뷰어. Vercel 배포.

## 웹 기록 동기화 (이번에 추가)
- 앱의 `SupabaseSync`(`app/.../data/SupabaseSync.kt`)가 OkHttp로 Supabase에 best-effort 동기화:
  - `PrintWorker`: 전송 시작 시 `uploading`, 종료 시 `success`/`failed` + 썸네일(≤1024px JPEG) 업로드
  - `LogsActivity` 삭제/전체삭제 시 원격도 삭제
- 멱등 키: `id = "{ANDROID_ID}_{logId}"` (upsert, `Prefer: resolution=merge-duplicates`)
- 동기화 실패는 모두 try/catch로 삼켜 인쇄 흐름에 영향 없음

## Supabase (공유 인스턴스 `pvhntshaadmbmpskwqmg`)
- 테이블 `camera_print_logs`, 버킷 `camera-print`(공개)
- **최초 1회**: `supabase_setup.sql`을 Supabase SQL editor에 실행해야 함 (테이블/RLS/버킷/정책 생성)
- anon 키로 read/write (단일 사용자 신뢰 앱)

## 웹 (web/)
- PIN 게이트 **2573**, 5초 폴링, 파일명 검색 + 상태 필터, 썸네일 클릭 시 원본 라이트박스
- 상태 색상은 앱 기록탭과 동일(대기 회색 / 전송중 파랑 / 완료 초록 / 실패 빨강)
- 로컬 실행: `cd web && npm install && npm run dev`
- 배포: `cd web && vercel --prod` (Vercel root = `web/`)

## 빌드
- 앱: `./gradlew assembleRelease` (JDK17). 현재 versionCode 3 / 1.2
- 함정: 동기화는 Supabase 테이블/버킷이 먼저 생성돼 있어야 작동 (없으면 그냥 무시됨)
