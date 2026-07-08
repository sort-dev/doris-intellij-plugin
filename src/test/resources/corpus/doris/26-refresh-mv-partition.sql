REFRESH MATERIALIZED VIEW acme_derived.events_rollup_mv PARTITION(p_20260617000000_20260618000000);
REFRESH MATERIALIZED VIEW acme_derived.events_rollup_mv PARTITIONS(p_a, p_b);
REFRESH MATERIALIZED VIEW acme_derived.events_rollup_mv AUTO;
