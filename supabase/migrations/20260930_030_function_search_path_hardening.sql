alter function app.reject_append_only_mutation()
  set search_path = pg_catalog, app;

alter function app.reject_final_receipt_mutation()
  set search_path = pg_catalog, app;

alter function app.enqueue_realtime_domain_event()
  set search_path = pg_catalog, app;
