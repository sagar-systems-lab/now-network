do $$
declare
  missing text;
begin
  with expected(function_name) as (
    values
      ('reject_append_only_mutation'),
      ('reject_final_receipt_mutation'),
      ('enqueue_realtime_domain_event')
  ),
  inspected as (
    select
      e.function_name,
      exists (
        select 1
        from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
        cross join lateral unnest(coalesce(p.proconfig, '{}'::text[])) cfg
        where n.nspname = 'app'
          and p.proname = e.function_name
          and cfg like 'search_path=%pg_catalog%app%'
      ) as hardened
    from expected e
  )
  select string_agg(function_name, ', ' order by function_name)
  into missing
  from inspected
  where hardened = false;

  if missing is not null then
    raise exception 'mutable or missing search_path: %', missing;
  end if;
end
$$;
