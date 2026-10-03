-- Durable opt-in catalog notifications, separate from push transport.
create or replace function app.notify_available_opportunity() returns trigger
language plpgsql set search_path=pg_catalog,app,extensions as $$
begin
  if new.status::text <> 'AVAILABLE' or old.status is not distinct from new.status then return new; end if;
  insert into app.notifications(actor_id,event_key,category,title,body,destination,entity_id)
  select distinct p.actor_id,'opportunity:'||new.refresh_id,'opportunities',
    'A nearby refresh is available','Open NOW to review the current reward and proof requirements.','opportunity',new.refresh_id
  from app.actor_preferences p join app.actors actor on actor.actor_id=p.actor_id and actor.status='ACTIVE'
  join app.state_definitions sd on sd.state_id=new.state_id
  join app.locations target on target.location_id=sd.location_id
  where p.actor_id<>new.requester_actor_id and p.notification_preferences->>'opportunities'='true'
    and new.refresh_expires_at>now()
    and exists(select 1 from jsonb_array_elements_text(p.notification_preferences->'area_ids') selected(id)
      join app.locations area on area.location_id=selected.id::uuid
      where extensions.st_dwithin(target.center,area.center,3000))
  on conflict(actor_id,event_key) do nothing;
  return new;
end $$;
revoke all on function app.notify_available_opportunity() from public;
create trigger available_opportunity_notification after update of status on app.refresh_requests
for each row execute function app.notify_available_opportunity();

create or replace function app.notify_device_access() returns trigger
language plpgsql set search_path=pg_catalog,app as $$
begin
  if tg_op='INSERT' then
    insert into app.notifications(actor_id,event_key,category,title,body,destination)
    values(new.actor_id,'device:'||new.installation_id,'security','A device connected',
      'Review connected devices in Settings to manage account access.','wallet')
    on conflict(actor_id,event_key) do nothing;
  elsif old.revoked_at is null and new.revoked_at is not null then
    insert into app.notifications(actor_id,event_key,category,title,body,destination)
    values(new.actor_id,'device-revoked:'||new.installation_id,'security','Device access revoked',
      'The revoked session can no longer access your NOW account.','wallet')
    on conflict(actor_id,event_key) do nothing;
  end if;
  return new;
end $$;
revoke all on function app.notify_device_access() from public;
create trigger device_access_notification after insert or update of revoked_at on app.installations
for each row execute function app.notify_device_access();
