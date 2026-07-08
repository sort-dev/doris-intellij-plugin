CREATE RESOURCE 'acme_s3_resource' PROPERTIES (
    'type' = 's3',
    's3.endpoint' = 's3.acme-cloud.example',
    's3.region' = 'us-east-1',
    's3.bucket' = 'acme-bucket'
);
CREATE WORKLOAD GROUP 'etl' PROPERTIES (
    'cpu_share' = '10',
    'memory_limit' = '30%'
);
