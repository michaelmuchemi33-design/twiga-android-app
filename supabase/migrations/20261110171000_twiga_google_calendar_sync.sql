alter table public.twiga_appointments
  add column if not exists google_event_id text,
  add column if not exists google_event_url text,
  add column if not exists google_synced_at timestamptz;

create index if not exists twiga_appointments_google_sync_idx
  on public.twiga_appointments(business_id, scheduled_at)
  where google_event_id is null;

-- Keep business profiles readable but require a live paid/trial entitlement to edit them.
drop policy if exists "Business members manage business profile" on public.twiga_business_profiles;
drop policy if exists "Business members read business profile" on public.twiga_business_profiles;
create policy "Business members read business profile"
on public.twiga_business_profiles for select to authenticated
using (exists (
  select 1 from public.business_members bm
  where bm.business_id = twiga_business_profiles.business_id and bm.user_id = auth.uid()
));
create policy "Business members insert business profile with paid access"
on public.twiga_business_profiles for insert to authenticated
with check (
  exists (select 1 from public.business_members bm where bm.business_id = twiga_business_profiles.business_id and bm.user_id = auth.uid())
  and private.twiga_has_paid_access(business_id)
);
create policy "Business members update business profile with paid access"
on public.twiga_business_profiles for update to authenticated
using (
  exists (select 1 from public.business_members bm where bm.business_id = twiga_business_profiles.business_id and bm.user_id = auth.uid())
  and private.twiga_has_paid_access(business_id)
)
with check (
  exists (select 1 from public.business_members bm where bm.business_id = twiga_business_profiles.business_id and bm.user_id = auth.uid())
  and private.twiga_has_paid_access(business_id)
);
