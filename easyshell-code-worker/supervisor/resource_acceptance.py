"""Single fixed memcg probe followed by a normal JS task. No arbitrary command or source."""
import argparse
import json

from .control import Supervisor, fingerprint
from .docker_adapter import Config, DockerAdapter
from .host_guard import HostGuard


def run(config, journal, session):
    try:
        observations = []
        for profile, request_id in (('resourceprobe', 'resource-acceptance-memcg-v1'),
                                    ('execution', 'resource-acceptance-recovery-v1')):
            from dataclasses import replace
            adapter = DockerAdapter(replace(config, profile=profile), guard=HostGuard())
            adapter.reaper_session = session
            adapter.preflight([])
            supervisor = Supervisor(adapter, journal, config.image, profile=profile)
            value = {'requestId': request_id, 'language': 'javascript',
                     'source': '' if profile == 'resourceprobe' else 'function main(i) { return {n:4}; }',
                     'input': {}, 'timeoutMs': 5000}
            result = supervisor.execute(value)
            adapter.set_deadline(adapter.clock() + 2)
            adapter.preflight([])
            expected = ({'status': 'failed', 'error': 'RESOURCE_EXHAUSTED'} if profile == 'resourceprobe'
                        else {'status': 'succeeded', 'output': {'n': 4}})
            # RESOURCE_EXHAUSTED is only produced from owned Inspect.OOMKilled is True.
            receipt = journal.cancel_receipt(request_id, fingerprint(value), session)
            if result != expected or not receipt['cleanupVerified']:
                return {'status': 'failed', 'error': 'RESOURCE_PROBE_UNVERIFIED', 'case': profile}
            observations.append({'case': profile, 'cleaned': True})
        return {'status': 'passed', 'observation': 'memcg_oom_and_js_recovery', 'cases': observations}
    except Exception:
        return {'status': 'failed', 'error': 'RESOURCE_ACCEPTANCE_UNVERIFIED'}


def main():
    parser = argparse.ArgumentParser(description='Fixed memory cgroup acceptance; dedicated runtime only')
    for name in ('socket', 'expected-daemon-id', 'image', 'journal', 'reaper-session'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    from .cancel import open_journal
    journal = open_journal(args.journal)
    try:
        if journal.db.execute('SELECT count(*) FROM tasks').fetchone()[0]:
            raise ValueError('fresh journal required')
        report = run(Config(args.socket, args.expected_daemon_id, args.image, 'resourceprobe'), journal,
                     args.reaper_session)
    except Exception:
        report = {'status': 'failed', 'error': 'RESOURCE_ACCEPTANCE_UNVERIFIED'}
    finally:
        journal.close()
    print(json.dumps(report, separators=(',', ':')))
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
