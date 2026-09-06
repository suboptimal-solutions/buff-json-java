import copy
import io
import json
import os
import re
import subprocess
import tempfile
import urllib.request
from pathlib import Path
import sys
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import report
import publish

BASE = 'a' * 40
HEAD = 'b' * 40
HARNESS = 'c' * 40


def fixture(score=1000.0, allocation=100.0, java='21'):
    return [{'benchmark': report.PREFIX + method, 'mode': 'thrpt', 'jmhVersion': '1.37',
             'jdkVersion': java + '.0.1', 'vmName': 'OpenJDK', 'vmVersion': java + '.0.1',
             'jvmArgs': ['-Xms256m', '-Xmx256m'], 'forks': 2, 'warmupIterations': 3,
             'warmupTime': '1 s', 'measurementIterations': 4, 'measurementTime': '1 s', 'threads': 1,
             'primaryMetric': {'score': score, 'scoreError': 5.0, 'scoreUnit': 'ops/s',
                               'rawData': [[score] * 4, [score] * 4]},
             'secondaryMetrics': {'gc.alloc.rate.norm': {'score': allocation, 'scoreUnit': 'B/op'}}}
            for method in report.METHODS]


def summary(java='21'):
    return report.compare(fixture(java=java), fixture(1100.0, 80.0, java),
                          {'base_sha': BASE, 'candidate_sha': HEAD, 'harness_sha': HARNESS, 'java': java})


def archive(data, name='summary.json'):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w') as package:
        package.writestr(name, json.dumps(data))
    return stream.getvalue()


class Reports(unittest.TestCase):
    def test_throughput_direction_and_allocation(self):
        result = report.compare(fixture(), fixture(800, 130),
                                {'base_sha': BASE, 'candidate_sha': HEAD, 'harness_sha': HARNESS, 'java': '21'})
        row = result['rows'][0]
        self.assertAlmostEqual(row['throughput_percent'], -20)
        self.assertEqual(row['timing'], 'regression signal')
        self.assertTrue(row['allocation_alert'])
        self.assertIn('regression signal', report.render(result))

    def test_noise_is_not_a_regression(self):
        before, after = fixture(), fixture(800)
        for item in after:
            item['primaryMetric']['scoreError'] = 300
        result = report.compare(before, after, summary()['metadata'])
        self.assertEqual(result['rows'][0]['timing'], 'inconclusive')

    def test_small_allocation_deltas_and_zero_baseline(self):
        b = {'score': 100, 'error': 1, 'allocation': 0}
        c = {'score': 100, 'error': 1, 'allocation': 16}
        self.assertFalse(report.classify(b, c)[4])
        c['allocation'] = 17
        self.assertTrue(report.classify(b, c)[4])
        b['allocation'], c['allocation'] = 1000, 1020
        self.assertFalse(report.classify(b, c)[4])

    def test_missing_duplicate_nonfinite_and_wrong_units_fail(self):
        corruptions = []
        values = fixture(); values.pop(); corruptions.append(values)
        values = fixture(); values[1] = copy.deepcopy(values[0]); corruptions.append(values)
        values = fixture(); values[0]['primaryMetric']['scoreError'] = float('nan'); corruptions.append(values)
        values = fixture(); values[0]['primaryMetric']['score'] = 0; corruptions.append(values)
        values = fixture(); values[0]['primaryMetric']['scoreUnit'] = 'ms/op'; corruptions.append(values)
        values = fixture(); values[0]['secondaryMetrics'] = {}; corruptions.append(values)
        values = fixture(); values[0]['primaryMetric']['rawData'][1].pop(); corruptions.append(values)
        for values in corruptions:
            with self.subTest(values=values[0]['benchmark']):
                with self.assertRaises((ValueError, KeyError)):
                    report.measurements(values)

    def test_compiler_runtime_and_jmh_protocol_must_match(self):
        for key, value in [('jdkVersion', '25.0.1'), ('jmhVersion', '1.38'), ('jvmArgs', ['-Xmx1g'])]:
            values = fixture()
            for item in values:
                item[key] = value
            with self.assertRaises(ValueError):
                report.compare(fixture(), values, summary()['metadata'])

    def test_publisher_recomputes_labels(self):
        data = summary()
        data['rows'][0]['timing'] = '@everyone forged verdict'
        data['rows'][0]['throughput_percent'] = -999
        result = report.render(data)
        self.assertNotIn('forged', result)
        self.assertIn('+10.0%', result)

    def test_unexpected_names_smoke_and_protocol_are_rejected(self):
        data = summary(); data['rows'][0]['method'] = '<script>alert(1)</script>'
        with self.assertRaises(ValueError): report.validate(data)
        data = summary(); data['profile'] = 'smoke'
        with self.assertRaises(ValueError): report.validate(data)
        data = summary(); data['environment']['forks'] = 1
        with self.assertRaises(ValueError): report.validate(data)


class Artifacts(unittest.TestCase):
    def test_valid_identity(self):
        self.assertEqual(publish.read_summary(archive(summary()), '21', HEAD)['metadata']['candidate_sha'], HEAD)

    def test_wrong_commit_jdk_path_and_oversized_json(self):
        for content, java, sha in [(archive(summary()), '21', BASE), (archive(summary()), '25', HEAD),
                                   (archive(summary(), '../summary.json'), '21', HEAD)]:
            with self.assertRaises(ValueError): publish.read_summary(content, java, sha)
        data = summary(); data['padding'] = 'x' * publish.MAX_JSON_BYTES
        with self.assertRaises(ValueError): publish.read_summary(archive(data), '21', HEAD)


class FakeGitHub:
    repository = 'owner/repo'

    def __init__(self, *, stale=False, missing=False, existing=False, event='pull_request'):
        self.stale, self.missing, self.existing = stale, missing, existing
        self.run = {'workflow_id': 7, 'event': event, 'status': 'completed', 'head_sha': HEAD,
                    'conclusion': 'success', 'pull_requests': [{'head': {'sha': HEAD}, 'base': {'sha': BASE}}]}
        self.writes = []

    def request(self, path, method='GET', data=None, binary=False):
        if method != 'GET':
            self.writes.append((path, method, data)); return {}
        if path == '/actions/runs/1': return self.run
        if path == '/actions/workflows/7': return {'path': '.github/workflows/performance.yml'}
        if path.startswith('/actions/artifacts/'):
            return archive(summary('21' if path.endswith('/21/zip') else '25'))
        if path == '/pulls/4':
            return {'state': 'open', 'number': 4, 'head': {'sha': BASE if self.stale else HEAD},
                    'base': {'sha': BASE, 'repo': {'full_name': self.repository}}}
        raise AssertionError(path)

    def pages(self, path, key=None):
        if path.endswith('/artifacts'):
            return iter([] if self.missing else [{'name': 'performance-summary-java' + j, 'id': int(j),
                         'expired': False, 'size_in_bytes': 20000} for j in ('21', '25')])
        if '/check-runs?' in path: return iter([])
        if path.endswith('/pulls'): return iter([{'number': 4}])
        if path == '/issues/4/comments':
            return iter([{'id': 9, 'user': {'login': 'github-actions[bot]'}, 'body': publish.MARKER}] if self.existing else [])
        raise AssertionError(path)


class Publishing(unittest.TestCase):
    def test_comment_and_check(self):
        api = FakeGitHub()
        publish.publish(api, 1)
        self.assertEqual([w[0] for w in api.writes], ['/check-runs', '/issues/4/comments'])
        self.assertEqual(api.writes[0][2]['conclusion'], 'neutral')

    def test_update_instead_of_comment_spam(self):
        api = FakeGitHub(existing=True); publish.publish(api, 1)
        self.assertEqual(api.writes[-1][:2], ('/issues/comments/9', 'PATCH'))

    def test_stale_pr_and_main_push_only_get_commit_check(self):
        for api in (FakeGitHub(stale=True), FakeGitHub(event='push')):
            publish.publish(api, 1)
            self.assertEqual([w[0] for w in api.writes], ['/check-runs'])

    def test_missing_results_are_failure_not_green(self):
        api = FakeGitHub(missing=True); publish.publish(api, 1)
        self.assertEqual(api.writes[0][2]['conclusion'], 'failure')
        self.assertEqual(api.writes[-1][0], '/issues/4/comments')
        self.assertIn('incomplete or invalid', api.writes[-1][2]['body'])



class Redirects(unittest.TestCase):
    def test_api_token_not_forwarded_to_artifact_host(self):
        request = urllib.request.Request('https://api.github.com/artifact', headers={'Authorization': 'Bearer secret'})
        redirected = publish.SafeRedirect().redirect_request(request, None, 302, 'Found', {}, 'https://artifact.example/file')
        self.assertIsNone(redirected.get_header('Authorization'))
        with self.assertRaises(ValueError):
            publish.SafeRedirect().redirect_request(request, None, 302, 'Found', {}, 'http://artifact.example/file')


class AllocationGate(unittest.TestCase):
    def test_valid_over_budget_missing_and_nonfinite_data(self):
        root = Path(__file__).resolve().parents[3]
        script = root / 'allocation-check.sh'
        budgets = dict(re.findall(r'"(io\.suboptimal\.buffjson\.benchmarks\.[^":]+):(\d+)"', script.read_text()))
        good = [{'benchmark': name, 'secondaryMetrics': {'gc.alloc.rate.norm':
                 {'score': float(limit) / 2, 'scoreUnit': 'B/op'}}} for name, limit in budgets.items()]
        cases = [(good, 0)]
        over = copy.deepcopy(good); over[0]['secondaryMetrics']['gc.alloc.rate.norm']['score'] = 1e9; cases.append((over, 1))
        absent = copy.deepcopy(good); absent[0]['secondaryMetrics'] = {}; cases.append((absent, 1))
        invalid = copy.deepcopy(good); invalid[0]['secondaryMetrics']['gc.alloc.rate.norm']['score'] = 'NaN'; cases.append((invalid, 1))
        cases.append((good[:-1], 1))
        with tempfile.TemporaryDirectory() as work:
            work = Path(work)
            (work / 'benchmarks.jar').touch()
            fake_java = work / 'java'
            fake_java.write_text('#!' + sys.executable + '\nimport os,shutil\nshutil.copyfile(os.environ["FAKE_RESULTS"], os.environ["RESULTS_FILE"])\n')
            fake_java.chmod(0o755)
            for data, expected in cases:
                (work / 'input.json').write_text(json.dumps(data))
                env = dict(os.environ, PATH=str(work) + os.pathsep + os.environ['PATH'],
                           BENCHMARKS_JAR=str(work / 'benchmarks.jar'), FAKE_RESULTS=str(work / 'input.json'),
                           RESULTS_FILE=str(work / 'result.json'), LOG_FILE=str(work / 'jmh.log'))
                result = subprocess.run(['bash', str(script)], env=env, capture_output=True, text=True)
                self.assertEqual(result.returncode, expected, result.stdout + result.stderr)

if __name__ == '__main__':
    unittest.main()
