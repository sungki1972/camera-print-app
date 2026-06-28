-- ===============================================================
-- 카메라 프린트 앱 — 웹 기록탭용 Supabase 설정
-- Supabase SQL editor 에 통째로 복사해서 실행하세요.
-- 프로젝트: pvhntshaadmbmpskwqmg
-- ===============================================================

-- 1) 기록 테이블 ------------------------------------------------
create table if not exists public.camera_print_logs (
  id            text primary key,             -- "{device_id}_{logId}" (멱등 upsert 키)
  device_id     text,
  log_id        bigint,
  file_name     text not null,
  status        text not null default 'pending',  -- pending | uploading | success | failed
  error_message text,
  image_url     text,                          -- Storage 공개 URL (썸네일)
  created_at    timestamptz not null default now(),
  completed_at  timestamptz,
  synced_at     timestamptz not null default now()
);

create index if not exists camera_print_logs_created_idx
  on public.camera_print_logs (created_at desc);

-- 2) RLS — 단일 사용자 신뢰 앱이므로 anon 읽기/쓰기 허용 -----------
alter table public.camera_print_logs enable row level security;

drop policy if exists "anon_select" on public.camera_print_logs;
create policy "anon_select" on public.camera_print_logs
  for select to anon using (true);

drop policy if exists "anon_insert" on public.camera_print_logs;
create policy "anon_insert" on public.camera_print_logs
  for insert to anon with check (true);

drop policy if exists "anon_update" on public.camera_print_logs;
create policy "anon_update" on public.camera_print_logs
  for update to anon using (true) with check (true);

drop policy if exists "anon_delete" on public.camera_print_logs;
create policy "anon_delete" on public.camera_print_logs
  for delete to anon using (true);

-- 3) Storage 버킷 (공개) ---------------------------------------
insert into storage.buckets (id, name, public)
values ('camera-print', 'camera-print', true)
on conflict (id) do update set public = true;

-- 4) Storage 정책 — camera-print 버킷에 anon 업로드/조회 허용 ------
drop policy if exists "cp_read" on storage.objects;
create policy "cp_read" on storage.objects
  for select to anon using (bucket_id = 'camera-print');

drop policy if exists "cp_insert" on storage.objects;
create policy "cp_insert" on storage.objects
  for insert to anon with check (bucket_id = 'camera-print');

drop policy if exists "cp_update" on storage.objects;
create policy "cp_update" on storage.objects
  for update to anon using (bucket_id = 'camera-print') with check (bucket_id = 'camera-print');

drop policy if exists "cp_delete" on storage.objects;
create policy "cp_delete" on storage.objects
  for delete to anon using (bucket_id = 'camera-print');
