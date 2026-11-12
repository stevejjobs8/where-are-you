-- 2단계: 이름 + 짝꿍 연결
alter table public.live_location add column if not exists name text;
alter table public.live_location add column if not exists partner text;   -- 짝꿍의 view_code

-- 짝꿍 초대 코드 (8자리, 10분 동안 한 번만 사용 가능)
create table if not exists public.pair_invite (
  code text primary key,
  view_code text not null references public.live_location(view_code) on delete cascade,
  expires_at timestamptz not null
);
alter table public.pair_invite enable row level security;

create or replace function public.set_name(p_view_code text, p_write_key text, p_name text)
returns void language plpgsql security definer set search_path = public as $$
begin
  update live_location set name = left(trim(p_name), 20)
   where view_code = p_view_code and write_key = p_write_key;
  if not found then raise exception 'invalid code'; end if;
end $$;

create or replace function public.pair_create(p_view_code text, p_write_key text)
returns text language plpgsql security definer set search_path = public, extensions as $$
declare b bytea; c text;
begin
  perform 1 from live_location where view_code = p_view_code and write_key = p_write_key;
  if not found then raise exception 'invalid code'; end if;
  delete from pair_invite where view_code = p_view_code or expires_at < now();
  loop
    b := gen_random_bytes(8); c := '';
    for i in 0..7 loop c := c || substr('ABCDEFGHJKLMNPQRSTUVWXYZ23456789', (get_byte(b,i) % 32) + 1, 1); end loop;
    exit when not exists (select 1 from pair_invite where code = c);
  end loop;
  insert into pair_invite(code, view_code, expires_at) values (c, p_view_code, now() + interval '10 minutes');
  return c;
end $$;

create or replace function public.pair_accept(p_code text, p_view_code text, p_write_key text)
returns text language plpgsql security definer set search_path = public as $$
declare other text;
begin
  perform 1 from live_location where view_code = p_view_code and write_key = p_write_key;
  if not found then raise exception 'invalid code'; end if;
  select view_code into other from pair_invite
   where code = upper(replace(replace(p_code,'-',''),' ','')) and expires_at > now();
  if other is null then raise exception 'invite expired'; end if;
  if other = p_view_code then raise exception 'own code'; end if;
  update live_location set partner = other where view_code = p_view_code;
  update live_location set partner = p_view_code where view_code = other;
  delete from pair_invite where view_code = other;
  return other;
end $$;

create or replace function public.get_partner(p_view_code text, p_write_key text)
returns table(partner text, partner_name text)
language sql security definer set search_path = public as $$
  select l.partner, p.name
    from live_location l left join live_location p on p.view_code = l.partner
   where l.view_code = p_view_code and l.write_key = p_write_key;
$$;

-- 보기: 이름도 함께. 공유가 꺼져 있으면 좌표는 비움
drop function if exists public.get_location(text);
create function public.get_location(p_view_code text)
returns table(lat double precision, lng double precision, accuracy real, speed real, heading real,
              activity text, updated_at timestamptz, name text, sharing boolean)
language sql security definer set search_path = public as $$
  select case when sharing then lat end, case when sharing then lng end,
         accuracy, speed, heading, activity, updated_at, name, sharing
    from live_location where view_code = p_view_code;
$$;

grant execute on function public.set_name(text,text,text) to anon;
grant execute on function public.pair_create(text,text) to anon;
grant execute on function public.pair_accept(text,text,text) to anon;
grant execute on function public.get_partner(text,text) to anon;
grant execute on function public.get_location(text) to anon;
