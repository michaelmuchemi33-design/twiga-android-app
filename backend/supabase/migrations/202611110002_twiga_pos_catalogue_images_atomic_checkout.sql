-- TWIGA POS catalogue, private product photo storage and atomic checkout.
alter table public.twiga_products
  add column if not exists item_type text not null default 'product',
  add column if not exists track_stock boolean not null default true;

alter table public.twiga_products
  drop constraint if exists twiga_products_item_type_check;
alter table public.twiga_products
  add constraint twiga_products_item_type_check
  check (item_type in ('product','service'));

alter table public.twiga_orders
  add column if not exists payment_method text not null default 'Pay later';

insert into storage.buckets (id,name,public,file_size_limit,allowed_mime_types)
values ('twiga-product-images','twiga-product-images',false,5242880,array['image/jpeg','image/png','image/webp'])
on conflict (id) do update set public=false,file_size_limit=5242880,allowed_mime_types=array['image/jpeg','image/png','image/webp'];

do $policies$
begin
  if not exists(select 1 from pg_policies where schemaname='storage' and tablename='objects' and policyname='TWIGA business members read product images') then
    create policy "TWIGA business members read product images" on storage.objects for select to authenticated
    using(bucket_id='twiga-product-images' and exists(select 1 from public.business_members bm where bm.user_id=(select auth.uid()) and bm.business_id=(storage.foldername(name))[1]::uuid));
  end if;
  if not exists(select 1 from pg_policies where schemaname='storage' and tablename='objects' and policyname='TWIGA business members upload product images') then
    create policy "TWIGA business members upload product images" on storage.objects for insert to authenticated
    with check(bucket_id='twiga-product-images' and exists(select 1 from public.business_members bm where bm.user_id=(select auth.uid()) and bm.business_id=(storage.foldername(name))[1]::uuid));
  end if;
  if not exists(select 1 from pg_policies where schemaname='storage' and tablename='objects' and policyname='TWIGA business members update product images') then
    create policy "TWIGA business members update product images" on storage.objects for update to authenticated
    using(bucket_id='twiga-product-images' and exists(select 1 from public.business_members bm where bm.user_id=(select auth.uid()) and bm.business_id=(storage.foldername(name))[1]::uuid))
    with check(bucket_id='twiga-product-images' and exists(select 1 from public.business_members bm where bm.user_id=(select auth.uid()) and bm.business_id=(storage.foldername(name))[1]::uuid));
  end if;
  if not exists(select 1 from pg_policies where schemaname='storage' and tablename='objects' and policyname='TWIGA business members delete product images') then
    create policy "TWIGA business members delete product images" on storage.objects for delete to authenticated
    using(bucket_id='twiga-product-images' and exists(select 1 from public.business_members bm where bm.user_id=(select auth.uid()) and bm.business_id=(storage.foldername(name))[1]::uuid));
  end if;
end
$policies$;

create or replace function public.twiga_pos_checkout(
  p_business_id uuid,
  p_customer_name text,
  p_customer_phone text,
  p_payment_method text,
  p_items jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $function$
declare
  v_user uuid := auth.uid();
  v_item jsonb;
  v_product public.twiga_products%rowtype;
  v_product_id uuid;
  v_qty numeric;
  v_line_total numeric;
  v_total numeric := 0;
  v_items jsonb := '[]'::jsonb;
  v_summary text := '';
  v_method text := coalesce(nullif(trim(p_payment_method), ''), 'Pay later');
  v_paid boolean;
  v_next bigint;
  v_order_id uuid;
  v_order_number text;
  v_customer text := coalesce(nullif(trim(p_customer_name), ''), 'Walk-in customer');
begin
  if v_user is null then raise exception 'Sign in to TWIGA before completing a sale.'; end if;
  if p_business_id is null or not exists (
    select 1 from public.business_members bm where bm.business_id=p_business_id and bm.user_id=v_user
  ) then raise exception 'You do not have access to this TWIGA business.'; end if;
  if not private.twiga_has_paid_access(p_business_id) then
    raise exception 'TWIGA workspace access is paused. Renew the business plan to use POS checkout.';
  end if;
  if v_method not in ('Cash','Pay later') then raise exception 'Only Cash and Pay later checkout are currently enabled.'; end if;
  if jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items)=0 then
    raise exception 'Add at least one item before completing the sale.';
  end if;

  for v_item in select value from jsonb_array_elements(p_items) loop
    begin
      v_product_id := (v_item->>'product_id')::uuid;
      v_qty := (v_item->>'quantity')::numeric;
    exception when others then raise exception 'The cart contains an invalid product or quantity.'; end;
    if v_qty is null or v_qty <= 0 or v_qty > 100000 then raise exception 'Every sale quantity must be greater than zero.'; end if;

    select * into v_product from public.twiga_products
    where id=v_product_id and business_id=p_business_id and is_active=true for update;
    if not found then raise exception 'A product in the cart is no longer available. Refresh the catalogue.'; end if;
    if v_product.track_stock and v_product.stock_quantity < v_qty then
      raise exception 'Insufficient stock for % (available: %).', v_product.name, v_product.stock_quantity;
    end if;
    if v_product.track_stock then
      update public.twiga_products set stock_quantity=stock_quantity-v_qty,updated_at=now()
      where id=v_product.id and business_id=p_business_id;
    end if;

    v_line_total := round(v_product.price*v_qty,2);
    v_total := v_total + v_line_total;
    v_items := v_items || jsonb_build_array(jsonb_build_object(
      'product_id',v_product.id,'name',v_product.name,'item_type',v_product.item_type,
      'quantity',v_qty,'unit_price',v_product.price,'line_total',v_line_total,'image_key',v_product.image_key
    ));
    v_summary := concat_ws(', ',nullif(v_summary,''),v_product.name || ' x' || v_qty::text);
  end loop;

  insert into public.twiga_order_counters(business_id,next_number) values(p_business_id,2)
  on conflict(business_id) do update set next_number=public.twiga_order_counters.next_number+1
  returning next_number-1 into v_next;
  v_order_number := 'TW-' || lpad(v_next::text,5,'0');
  v_paid := v_method='Cash';

  insert into public.twiga_orders(
    business_id,created_by,customer_name,customer_phone,total_amount,description,
    status,order_number,items,currency,payment_status,payment_method
  ) values(
    p_business_id,v_user,v_customer,nullif(trim(p_customer_phone),''),
    round(v_total,2),v_summary,case when v_paid then 'Paid' else 'Pending' end,
    v_order_number,v_items,'KES',case when v_paid then 'paid' else 'unpaid' end,v_method
  ) returning id into v_order_id;

  return jsonb_build_object(
    'id',v_order_id,'order_number',v_order_number,'customer_name',v_customer,
    'total_amount',round(v_total,2),'payment_status',case when v_paid then 'paid' else 'unpaid' end,'items',v_items
  );
end
$function$;

revoke all on function public.twiga_pos_checkout(uuid,text,text,text,jsonb) from public,anon;
grant execute on function public.twiga_pos_checkout(uuid,text,text,text,jsonb) to authenticated;
