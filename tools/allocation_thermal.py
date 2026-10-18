"""Host-only readiness policy for the separately versioned thermal continuation.

No device access or command execution. Callers retain the original scheduler's
budgets and append every observation to its immutable administrative evidence.
"""
from datetime import datetime


PROTOCOL = 'allocation-idle-thermal-0-1-v1'


def sample_readiness(sample, now, reservation_at):
    """Reject invalid/stale observations; return a bounded readiness decision.

    Timestamps are caller observations, not independent device/process proof.
    Freshness starts at query initiation, so slow queries cannot appear fresh.
    """
    if (sample.get('schema') != 'idle-thermal-observation-v1'
            or sample.get('protocol') != PROTOCOL
            or sample.get('probe', {}).get('schema') != 'idle-thermal-status-v1'
            or sample['probe'].get('source') != 'android-thermalservice'):
        raise ValueError('unrecognized thermal observation identity')
    status = sample['probe'].get('thermalStatus')
    if type(status) is not int or not 0 <= status <= 6:
        raise ValueError('missing or invalid thermal severity')
    try:
        staged, queried, completed = [datetime.fromisoformat(sample[key]) for key in
                                     ('staging_completed_at', 'query_started_at', 'query_completed_at')]
        if any(value.tzinfo is None for value in (staged, queried, completed, now, reservation_at)):
            raise ValueError('timezone required')
        if not reservation_at <= staged <= queried <= completed <= now:
            raise ValueError('invalid observation order')
        age = (now - queried).total_seconds()
        if age > 15:
            raise ValueError('stale thermal observation')
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError('invalid thermal observation timing: ' + str(error)) from error
    return dict(protocol=PROTOCOL, thermal_status=status, age_seconds=age,
                kind='ready' if status <= 1 else 'cooldown',
                poll_seconds=0 if status <= 1 else 15)
