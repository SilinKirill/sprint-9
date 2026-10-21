#!/bin/sh
set -eu
for topic in bionicpro.crm_events bionicpro.transaction bionicpro_connect_config bionicpro_connect_offsets bionicpro_connect_status __debezium-heartbeat.bionicpro; do
    case "$topic" in
        *connect_*) config=cleanup.policy=compact ;;
        *) config=retention.ms=-1 ;;
    esac
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists \
        --topic "$topic" --partitions 1 --replication-factor 1 --config "$config"
done
