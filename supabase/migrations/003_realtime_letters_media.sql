-- Realtime, read state, archive/delete, drafts, attachments and push-token schema.
-- Run after 001 and 002 in Supabase Dashboard > SQL Editor.

alter table public.letters add column if not exists is_read boolean not null default false;
alter table public.letters add column if not exists read_at timestamptz;
alter table public.letters add column if not exists archived_at timestamptz;
alter table public.letters add column if not exists deleted_at timestamptz;
alter table public.letters add column if not exists sender_user_id uuid references auth.users(id);
alter table public.letters add column if not exists status text not null default 'published';

alter table public.letters drop constraint if exists letters_status_check;
alter table public.letters add constraint letters_status_check
    check (status in ('draft', 'scheduled', 'published'));

create index if not exists letters_recipient_visible_idx
    on public.letters(owner_id, published_at desc)
    where deleted_at is null and archived_at is null;

drop policy if exists "owners read their letters" on public.letters;
drop policy if exists "recipient reads published mailbox letters" on public.letters;
drop policy if exists "recipient reads visible published letters" on public.letters;
create policy "recipient reads visible published letters"
on public.letters for select to authenticated
using (
    owner_id = (select auth.uid())
    and status in ('scheduled', 'published')
    and published_at <= now()
    and archived_at is null
    and deleted_at is null
);

create or replace function public.mark_letter_read(letter_id uuid)
returns void
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
    update public.letters
    set is_read = true,
        read_at = coalesce(read_at, now())
    where id = letter_id
      and owner_id = auth.uid()
      and deleted_at is null;
    if not found then raise exception 'letter_not_found'; end if;
end;
$$;

create or replace function public.archive_letter(letter_id uuid)
returns void
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
    update public.letters
    set archived_at = now()
    where id = letter_id
      and owner_id = auth.uid()
      and deleted_at is null;
    if not found then raise exception 'letter_not_found'; end if;
end;
$$;

create or replace function public.delete_letter(letter_id uuid)
returns void
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
    update public.letters
    set deleted_at = now()
    where id = letter_id
      and owner_id = auth.uid();
    if not found then raise exception 'letter_not_found'; end if;
end;
$$;

revoke all on function public.mark_letter_read(uuid) from public, anon;
revoke all on function public.archive_letter(uuid) from public, anon;
revoke all on function public.delete_letter(uuid) from public, anon;
grant execute on function public.mark_letter_read(uuid) to authenticated;
grant execute on function public.archive_letter(uuid) to authenticated;
grant execute on function public.delete_letter(uuid) to authenticated;

-- Server-side drafts for the sender. Existing iOS-local drafts can be uploaded later.
drop policy if exists "sender reads own drafts" on public.letters;
create policy "sender reads own drafts"
on public.letters for select to authenticated
using (
    sender_user_id = (select auth.uid())
    and status = 'draft'
    and deleted_at is null
);

drop policy if exists "sender writes own drafts" on public.letters;
create policy "sender writes own drafts"
on public.letters for all to authenticated
using (
    sender_user_id = (select auth.uid())
    and status = 'draft'
)
with check (
    sender_user_id = (select auth.uid())
    and status = 'draft'
    and exists (
        select 1 from public.mailbox_members mm
        where mm.mailbox_id = letters.mailbox_id
          and mm.user_id = (select auth.uid())
          and mm.role = 'sender'
    )
);

create table if not exists public.letter_attachments (
    id uuid primary key default gen_random_uuid(),
    letter_id uuid not null references public.letters(id) on delete cascade,
    mailbox_id uuid not null references public.mailboxes(id) on delete cascade,
    storage_path text not null unique,
    mime_type text not null,
    size_bytes bigint not null check (size_bytes between 1 and 6291456),
    created_by uuid not null default auth.uid() references auth.users(id),
    created_at timestamptz not null default now()
);

alter table public.letter_attachments enable row level security;
revoke all on public.letter_attachments from anon;
grant select, insert, delete on public.letter_attachments to authenticated;

drop policy if exists "members read letter attachments" on public.letter_attachments;
create policy "members read letter attachments"
on public.letter_attachments for select to authenticated
using (
    exists (
        select 1 from public.mailbox_members mm
        where mm.mailbox_id = letter_attachments.mailbox_id
          and mm.user_id = (select auth.uid())
    )
);

drop policy if exists "senders add letter attachments" on public.letter_attachments;
create policy "senders add letter attachments"
on public.letter_attachments for insert to authenticated
with check (
    created_by = (select auth.uid())
    and exists (
        select 1 from public.mailbox_members mm
        where mm.mailbox_id = letter_attachments.mailbox_id
          and mm.user_id = (select auth.uid())
          and mm.role = 'sender'
    )
);

insert into storage.buckets(id, name, public, file_size_limit, allowed_mime_types)
values (
    'letter-attachments',
    'letter-attachments',
    false,
    6291456,
    array['image/jpeg', 'image/png', 'image/webp', 'image/heic']
)
on conflict (id) do update set
    public = false,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "mailbox members read attachment objects" on storage.objects;
create policy "mailbox members read attachment objects"
on storage.objects for select to authenticated
using (
    bucket_id = 'letter-attachments'
    and exists (
        select 1 from public.mailbox_members mm
        where mm.mailbox_id::text = (storage.foldername(name))[1]
          and mm.user_id = (select auth.uid())
    )
);

drop policy if exists "mailbox senders upload attachment objects" on storage.objects;
create policy "mailbox senders upload attachment objects"
on storage.objects for insert to authenticated
with check (
    bucket_id = 'letter-attachments'
    and exists (
        select 1 from public.mailbox_members mm
        where mm.mailbox_id::text = (storage.foldername(name))[1]
          and mm.user_id = (select auth.uid())
          and mm.role = 'sender'
    )
);

-- Realtime requires the table in the supabase_realtime publication.
do $$
begin
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'letters'
    ) then
        alter publication supabase_realtime add table public.letters;
    end if;
end
$$;

-- FCM/APNs client tokens. Actual sending must happen in a trusted Edge Function
-- or server environment; no server credential belongs in either mobile app.
create table if not exists public.push_devices (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    platform text not null check (platform in ('android', 'ios')),
    token text not null unique,
    updated_at timestamptz not null default now()
);
alter table public.push_devices enable row level security;
revoke all on public.push_devices from anon;
grant select, insert, update, delete on public.push_devices to authenticated;
drop policy if exists "users manage own push devices" on public.push_devices;
create policy "users manage own push devices"
on public.push_devices for all to authenticated
using (user_id = (select auth.uid()))
with check (user_id = (select auth.uid()));
