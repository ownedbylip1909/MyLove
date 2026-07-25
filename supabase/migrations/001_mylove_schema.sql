-- Run this once in Supabase Dashboard > SQL Editor.
-- Also enable Authentication > Providers > Anonymous Sign-Ins.

create extension if not exists pgcrypto;

create table if not exists public.letters (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    title text not null check (char_length(title) between 1 and 120),
    preview text not null default '',
    body text not null check (char_length(body) between 1 and 10000),
    date_label text not null default 'NEU',
    published_at timestamptz not null default now(),
    created_at timestamptz not null default now()
);

create index if not exists letters_owner_published_idx
    on public.letters (owner_id, published_at desc);

alter table public.letters enable row level security;

revoke all on public.letters from anon;
grant select, insert, update, delete on public.letters to authenticated;

drop policy if exists "owners read their letters" on public.letters;
create policy "owners read their letters"
    on public.letters for select
    to authenticated
    using ((select auth.uid()) = owner_id and published_at <= now());

drop policy if exists "owners create their letters" on public.letters;
create policy "owners create their letters"
    on public.letters for insert
    to authenticated
    with check ((select auth.uid()) = owner_id);

drop policy if exists "owners update their letters" on public.letters;
create policy "owners update their letters"
    on public.letters for update
    to authenticated
    using ((select auth.uid()) = owner_id)
    with check ((select auth.uid()) = owner_id);

drop policy if exists "owners delete their letters" on public.letters;
create policy "owners delete their letters"
    on public.letters for delete
    to authenticated
    using ((select auth.uid()) = owner_id);
