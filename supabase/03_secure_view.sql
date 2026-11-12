-- 3단계: 보안 강화 — 연결된 짝꿍(앱, 또는 앱으로 인증한 웹 기기)만 위치를 볼 수 있음
--         + 열람 기록(일별 횟수, 6개월 보관) + 내 정보 삭제

-- 웹 기기: 앱에서 받은 1회용 코드로 연결된 브라우저. 토큰은 그 브라우저에만 저장됨
create table if not exists public.viewer_device (
  token text primary key,
  owner text not null references public.live_location(view_code) on delete cascade,
  label text,
  created_at timestamptz not null default now(),
  last_seen timestamptz
);
create table if not exists public.device_invite (
  code text primary key,
  owner text not null references public.live_location(view_code) on delete cascade,
  expires_at timestamptz not null
);
-- 열람 기록: 누가(viewer) 누구의(target) 위치를 하루에 몇 번 봤는지
create table if not exists public.access_daily (
  target text not null references public.live_location(view_code) on delete cascade,
  viewer text not null,
  day date not null,
  views int not null default 0,
  primary key (target, viewer, day)
);
alter table public.viewer_device enable row level security;
alter table public.device_invite enable row level security;
alter table public.access_daily enable row level security;

-- 공개 링크(?c=코드)로 보기는 이제 막음
revoke execute on function public.get_location(text) from anon, public;

create or replace function public._owner(p_view_code text, p_write_key text)
returns text language plpgsql security definer set search_path = public as $$
begin
  perform 1 from live_location where view_code = p_view_code and write_key = p_write_key;
  if not found then raise exception 'invalid code'; end if;
  return p_view_code;
end $$;

-- 내부용: owner의 짝꿍 위치를 돌려주고 열람 기록을 남김
create or replace function public._partner_location(p_owner text)
returns table(lat double precision, lng double precision, accuracy real, speed real, heading real,
              activity text, updated_at timestamptz, name text, sharing boolean)
language plpgsql security definer set search_path = public as $$
declare pt text;
begin
  select partner into pt from live_location where view_code = p_owner;
  if pt is null then return; end if;
  insert into access_daily(target, viewer, day, views) values (pt, p_owner, current_date, 1)
    on conflict (target, viewer, day) do update set views = access_daily.views + 1;
  if random() < 0.01 then delete from access_daily where day < current_date - 183; end if;   -- 6개월 지난 기록 정리
  return query select case when l.sharing then l.lat end, case when l.sharing then l.lng end,
         l.accuracy, l.speed, l.heading, l.activity, l.updated_at, l.name, l.sharing
    from live_location l where l.view_code = pt;
end $$;
revoke execute on function public._owner(text,text) from anon, public;
revoke execute on function public._partner_location(text) from anon, public;

-- 앱: 내 짝꿍 위치
create or replace function public.get_partner_location(p_view_code text, p_write_key text)
returns table(lat double precision, lng double precision, accuracy real, speed real, heading real,
              activity text, updated_at timestamptz, name text, sharing boolean)
language plpgsql security definer set search_path = public as $$
begin
  return query select * from _partner_location(_owner(p_view_code, p_write_key));
end $$;

-- 웹 기기 연결 코드 만들기 (앱에서)
create or replace function public.device_link_create(p_view_code text, p_write_key text)
returns text language plpgsql security definer set search_path = public, extensions as $$
declare b bytea; c text; o text := _owner(p_view_code, p_write_key);
begin
  delete from device_invite where owner = o or expires_at < now();
  loop
    b := gen_random_bytes(8); c := '';
    for i in 0..7 loop c := c || substr('ABCDEFGHJKLMNPQRSTUVWXYZ23456789', (get_byte(b,i) % 32) + 1, 1); end loop;
    exit when not exists (select 1 from device_invite where code = c);
  end loop;
  insert into device_invite(code, owner, expires_at) values (c, o, now() + interval '10 minutes');
  return c;
end $$;

-- 웹에서 코드 입력 → 이 브라우저 전용 토큰 발급
create or replace function public.device_link_accept(p_code text, p_label text)
returns text language plpgsql security definer set search_path = public, extensions as $$
declare o text; tk text := encode(gen_random_bytes(32),'hex');
begin
  select owner into o from device_invite where code = upper(replace(replace(p_code,'-',''),' ','')) and expires_at > now();
  if o is null and exists (select 1 from pair_invite where code = upper(replace(replace(p_code,'-',''),' ',''))) then raise exception 'pair code'; end if;
  if o is null then raise exception 'invite expired'; end if;
  delete from device_invite where owner = o;
  insert into viewer_device(token, owner, label) values (tk, o, left(coalesce(p_label,'웹 브라우저'), 60));
  return tk;
end $$;

-- 웹 기기: 토큰으로 짝꿍 위치 보기 (토큰은 90일 안 쓰면 만료)
create or replace function public.get_partner_location_web(p_token text)
returns table(lat double precision, lng double precision, accuracy real, speed real, heading real,
              activity text, updated_at timestamptz, name text, sharing boolean)
language plpgsql security definer set search_path = public as $$
declare o text;
begin
  select owner into o from viewer_device where token = p_token and coalesce(last_seen, created_at) > now() - interval '90 days';
  if o is null then raise exception 'device not linked'; end if;
  update viewer_device set last_seen = now() where token = p_token;
  return query select * from _partner_location(o);
end $$;

create or replace function public.device_unlink(p_token text)
returns void language sql security definer set search_path = public as $$
  delete from viewer_device where token = p_token;
$$;

-- 앱: 연결된 웹 기기 수 / 모두 끊기
create or replace function public.devices_count(p_view_code text, p_write_key text)
returns int language sql security definer set search_path = public as $$
  select count(*)::int from viewer_device where owner = _owner(p_view_code, p_write_key);
$$;
create or replace function public.devices_revoke_all(p_view_code text, p_write_key text)
returns void language sql security definer set search_path = public as $$
  delete from viewer_device where owner = _owner(p_view_code, p_write_key);
$$;

-- 앱: 최근 30일 동안 짝꿍이 내 위치를 본 횟수 (모아서 알리기)
create or replace function public.my_access_summary(p_view_code text, p_write_key text)
returns table(viewer_name text, views bigint, last_day date)
language sql security definer set search_path = public as $$
  select coalesce(v.name, '짝꿍'), sum(a.views), max(a.day)
    from access_daily a left join live_location v on v.view_code = a.viewer
   where a.target = _owner(p_view_code, p_write_key) and a.day >= current_date - 30
   group by v.name;
$$;

-- 앱: 짝꿍 연결 끊기 / 내 정보 완전 삭제
create or replace function public.unpair(p_view_code text, p_write_key text)
returns void language plpgsql security definer set search_path = public as $$
declare o text := _owner(p_view_code, p_write_key);
begin
  update live_location set partner = null where view_code = o or partner = o;
end $$;
create or replace function public.delete_me(p_view_code text, p_write_key text)
returns void language plpgsql security definer set search_path = public as $$
declare o text := _owner(p_view_code, p_write_key);
begin
  update live_location set partner = null where partner = o;
  delete from access_daily where viewer = o;
  delete from live_location where view_code = o;      -- 기기·초대·기록은 함께 삭제됨
end $$;

grant execute on function public.get_partner_location(text,text) to anon;
grant execute on function public.device_link_create(text,text) to anon;
grant execute on function public.device_link_accept(text,text) to anon;
grant execute on function public.get_partner_location_web(text) to anon;
grant execute on function public.device_unlink(text) to anon;
grant execute on function public.devices_count(text,text) to anon;
grant execute on function public.devices_revoke_all(text,text) to anon;
grant execute on function public.my_access_summary(text,text) to anon;
grant execute on function public.unpair(text,text) to anon;
grant execute on function public.delete_me(text,text) to anon;
