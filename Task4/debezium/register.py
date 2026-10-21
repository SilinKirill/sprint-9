"""Register the connector and wait until its task is running."""
import json
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

endpoint = 'http://kafka-connect:8083/connectors/bionicpro-crm'
request = Request(endpoint + '/config', method='PUT',
                  data=Path('/config/crm-connector.json').read_bytes(),
                  headers={'Content-Type': 'application/json'})
with urlopen(request, timeout=30) as response:
    response.read()
for attempt in range(60):
    try:
        with urlopen(endpoint + '/status', timeout=10) as response:
            status = json.load(response)
    except HTTPError as error:
        # Connect accepts the configuration before the distributed worker exposes status.
        if error.code != 404:
            raise
        time.sleep(2)
        continue
    states = [status['connector']['state']] + [task['state'] for task in status['tasks']]
    if 'FAILED' in states:
        raise RuntimeError('Debezium failed; inspect Kafka Connect logs')
    if len(status['tasks']) == 1 and all(state == 'RUNNING' for state in states):
        print('Debezium connector and task: RUNNING')
        break
    time.sleep(2)
else:
    raise RuntimeError('Debezium did not become ready')
