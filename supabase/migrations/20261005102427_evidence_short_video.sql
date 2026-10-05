alter table app.evidence_packets add column video_metadata jsonb;

alter table app.evidence_packets add constraint evidence_video_metadata_valid check (
  video_metadata is null or (
    jsonb_typeof(video_metadata) = 'object'
    and video_metadata ?& array['object_key','sha256','size_bytes','duration_ms',
      'capture_started_monotonic_ms','capture_completed_monotonic_ms']
    and video_metadata->>'object_key' = media_object_key || '.mp4'
    and video_metadata->>'sha256' ~ '^[a-f0-9]{64}$'
    and (video_metadata->>'size_bytes')::bigint between 1 and 6291456
    and (video_metadata->>'duration_ms')::bigint between 3000 and 15000
    and (video_metadata->>'capture_started_monotonic_ms')::bigint >= capture_completed_monotonic_ms
    and (video_metadata->>'capture_completed_monotonic_ms')::bigint >=
      (video_metadata->>'capture_started_monotonic_ms')::bigint
  ) is true
);

create unique index evidence_video_exact_replay_idx
  on app.evidence_packets ((video_metadata->>'sha256')) where video_metadata is not null;

create function app.guard_evidence_video() returns trigger
language plpgsql set search_path = '' as $$
begin
  if tg_op = 'UPDATE' then
    if new.video_metadata is distinct from old.video_metadata then
      raise exception 'Committed video metadata is immutable';
    end if;
  elsif exists (
    select 1 from app.refresh_requests r where r.refresh_id = new.refresh_id
      and r.proof_policy_snapshot->'capture'->>'video_required' = 'true'
  ) and new.video_metadata is null then
    raise exception 'This proof requires a video';
  end if;
  return new;
end;
$$;
revoke all on function app.guard_evidence_video() from public, anon, authenticated;
create trigger evidence_video_guard before insert or update on app.evidence_packets
  for each row execute function app.guard_evidence_video();

-- Keep the existing private bucket and size limit; allow the validated companion format.
do $$
begin
  if to_regclass('storage.buckets') is not null then
    update storage.buckets set allowed_mime_types =
      array(select distinct unnest(allowed_mime_types || array['video/mp4']))
    where id = 'now-evidence' and allowed_mime_types is not null;
  end if;
end;
$$;
