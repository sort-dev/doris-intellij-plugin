EXPORT TABLE acme_events
PARTITION (p20260701, p20260702)
TO "s3://acme-bucket/export/"
PROPERTIES (
    "format" = "parquet",
    "max_file_size" = "1024MB"
)
WITH s3 (
    "s3.endpoint" = "s3.acme-cloud.example",
    "s3.region" = "us-east-1",
    "s3.access_key" = "ak_example",
    "s3.secret_key" = "sk_example"
);
