CREATE ROUTINE LOAD acme_db.events_load ON acme_events
COLUMNS TERMINATED BY ",",
COLUMNS(user_id, event_ts, tags)
PROPERTIES (
    "desired_concurrent_number" = "3",
    "max_batch_interval" = "20",
    "max_batch_rows" = "300000",
    "format" = "json"
)
FROM KAFKA (
    "kafka_broker_list" = "broker1:9092,broker2:9092",
    "kafka_topic" = "acme_events_topic",
    "property.group.id" = "acme_loader",
    "property.kafka_default_offsets" = "OFFSET_BEGINNING"
);
