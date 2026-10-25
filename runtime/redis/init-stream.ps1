# Run after: docker compose up -d
docker compose exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli XGROUP CREATE stream.orders g1 0 MKSTREAM'
# BUSYGROUP means the consumer group already exists; no reset is needed.
