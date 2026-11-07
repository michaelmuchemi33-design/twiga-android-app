-- Store the business's existing public phone number during first-time onboarding.
alter table public.businesses
  add column if not exists phone_number text;

create index if not exists businesses_phone_number_idx
  on public.businesses (phone_number)
  where phone_number is not null;
