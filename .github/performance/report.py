"""Validate JMH measurements and render comparison reports (standard library only)."""
import math
import re

PREFIX = 'io.suboptimal.buffjson.benchmarks.EncodePathsBenchmark.'
METHODS = tuple(shape + path + encoding for shape in ('simple', 'complex', 'map', 'struct', 'timestamp')
                for path in ('Codegen', 'Typed', 'Reflection') for encoding in ('Utf16', 'Utf8'))
SMOKE_METHODS = ('simpleCodegenUtf16', 'mapTypedUtf8', 'structReflectionUtf16')
THROUGHPUT_THRESHOLD = 10.0
ALLOCATION_THRESHOLD = 5.0
SHA = re.compile(r'^[0-9a-f]{40}$')


def number(value, *, positive=False):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError('Expected a finite number')
    if value < 0 or value > 1e18 or (positive and value < 1e-9):
        raise ValueError('Expected a nonnegative number')
    return value


def measurements(raw, methods=METHODS):
    if not isinstance(raw, list) or len(raw) != len(methods):
        raise ValueError('Incomplete benchmark set')
    found = {}
    environment = None
    for entry in raw:
        name = entry['benchmark']
        if not name.startswith(PREFIX) or name[len(PREFIX):] not in methods or name in found:
            raise ValueError('Unexpected or duplicate benchmark: ' + name)
        if entry['mode'] != 'thrpt' or entry.get('params'):
            raise ValueError('Expected unparameterized throughput benchmarks')
        metric = entry['primaryMetric']
        if metric['scoreUnit'] != 'ops/s':
            raise ValueError('Expected ops/s')
        score = number(metric['score'], positive=True)
        error = number(metric['scoreError'])
        allocation = entry['secondaryMetrics']['gc.alloc.rate.norm']
        if allocation['scoreUnit'] != 'B/op':
            raise ValueError('Expected B/op')
        alloc = number(allocation['score'])
        samples = metric['rawData']
        if len(samples) != entry['forks'] or any(len(fork) != entry['measurementIterations'] for fork in samples):
            raise ValueError('Incomplete JMH forks/iterations')
        for fork in samples:
            for sample in fork:
                number(sample, positive=True)
        current = {key: entry[key] for key in ('jmhVersion', 'jdkVersion', 'vmName', 'vmVersion',
                   'jvmArgs', 'forks', 'warmupIterations', 'warmupTime', 'measurementIterations',
                   'measurementTime', 'threads')}
        if environment is not None and current != environment:
            raise ValueError('Mixed JMH/JVM settings in one result')
        environment = current
        found[name] = {'score': score, 'error': error, 'allocation': alloc}
    return found, environment


def classify(base, candidate):
    delta = (candidate['score'] / base['score'] - 1) * 100
    # JMH reports 99.9% confidence intervals. Separation is deliberately a
    # conservative alert heuristic, not a paired statistical significance test.
    slower = candidate['score'] + candidate['error'] < base['score'] - base['error']
    faster = candidate['score'] - candidate['error'] > base['score'] + base['error']
    timing = 'regression signal' if delta <= -THROUGHPUT_THRESHOLD and slower else (
        'improvement signal' if delta >= THROUGHPUT_THRESHOLD and faster else 'inconclusive')
    added = candidate['allocation'] - base['allocation']
    alloc_percent = added / base['allocation'] * 100 if base['allocation'] else None
    allocation_alert = added > 16 and (alloc_percent is None or alloc_percent > ALLOCATION_THRESHOLD)
    return delta, timing, added, alloc_percent, allocation_alert


def compare(base_raw, candidate_raw, metadata, methods=METHODS):
    base, base_env = measurements(base_raw, methods)
    candidate, candidate_env = measurements(candidate_raw, methods)
    if base_env != candidate_env:
        raise ValueError('Base and candidate JVM/JMH settings differ')
    rows = []
    for method in methods:
        before, after = base[PREFIX + method], candidate[PREFIX + method]
        delta, timing, added, alloc_percent, alloc_alert = classify(before, after)
        rows.append({'method': method, 'base': before, 'candidate': after,
                     'throughput_percent': delta, 'timing': timing,
                     'allocation_bytes_delta': added, 'allocation_percent': alloc_percent,
                     'allocation_alert': alloc_alert})
    return {'schema': 1, 'profile': 'full' if methods == METHODS else 'smoke',
            'metadata': metadata, 'environment': base_env, 'rows': rows}


def validate(summary, *, full=True):
    if summary['schema'] != 1 or summary['profile'] not in ('full', 'smoke'):
        raise ValueError('Unsupported report schema/profile')
    methods = METHODS if summary['profile'] == 'full' else SMOKE_METHODS
    if full and methods != METHODS:
        raise ValueError('Smoke data cannot be published as a full report')
    meta = summary['metadata']
    for key in ('base_sha', 'candidate_sha', 'harness_sha'):
        if not SHA.fullmatch(meta[key]):
            raise ValueError('Invalid source identity')
    if meta['java'] not in ('21', '25'):
        raise ValueError('Unexpected Java version')
    env = summary['environment']
    if not str(env['jdkVersion']).startswith(meta['java'] + '.'):
        raise ValueError('JDK does not match matrix identity')
    if full and (env['forks'] != 2 or env['threads'] != 1 or env['warmupIterations'] != 3
                 or env['measurementIterations'] != 4 or env['warmupTime'] != '1 s'
                 or env['measurementTime'] != '1 s' or env['jvmArgs'] != ['-Xms256m', '-Xmx256m']):
        raise ValueError('Unexpected measurement protocol')
    if [r['method'] for r in summary['rows']] != list(methods):
        raise ValueError('Incomplete or reordered report rows')
    for row in summary['rows']:
        for variant in ('base', 'candidate'):
            item = row[variant]
            number(item['score'], positive=True)
            number(item['error'])
            number(item['allocation'])
        # Recompute every classification; never trust artifact-provided prose.
        delta, timing, added, percent, alert = classify(row['base'], row['candidate'])
        row.update(throughput_percent=delta, timing=timing, allocation_bytes_delta=added,
                   allocation_percent=percent, allocation_alert=alert)
    return summary


def render(summary):
    validate(summary, full=False)
    meta = summary['metadata']
    lines = [f"### Java {meta['java']} performance", '',
             f"Base: {meta['base_sha']} → candidate: {meta['candidate_sha']}",
             f"Shared benchmark source: {meta['harness_sha']}", '',
             'Throughput alerts are advisory. Existing allocation budgets are enforced separately.',
             'A timing signal needs at least 10% change and separated JMH 99.9% intervals; otherwise it is inconclusive.',
             'Allocation alerts need both >5% and >16 B/op growth (or >16 B/op from zero).', '',
             '| Benchmark | Base ops/s | Candidate ops/s | Change | Timing | B/op base → candidate | Allocation |',
             '|---|---:|---:|---:|---|---:|---|']
    for row in summary['rows']:
        b, c = row['base'], row['candidate']
        lines.append(f"| {row['method']} | {b['score']:,.0f} ±{b['error']:,.0f} | "
                     f"{c['score']:,.0f} ±{c['error']:,.0f} | {row['throughput_percent']:+.1f}% | "
                     f"{row['timing']} | {b['allocation']:.1f} → {c['allocation']:.1f} | "
                     f"{'review increase' if row['allocation_alert'] else 'within alert threshold'} |")
    if summary['profile'] == 'smoke':
        lines += ['', '**Smoke run: validates the pipeline only; not performance evidence.**']
    return '\n'.join(lines) + '\n'
