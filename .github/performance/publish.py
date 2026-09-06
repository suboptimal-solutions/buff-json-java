"""Trusted workflow_run publisher; benchmark artifacts are data, never code."""
import io
import json
import os
from pathlib import Path
import urllib.request
import urllib.parse
import zipfile

from report import render, validate, SHA

MARKER = '<!-- buff-json-performance-report -->'
MAX_ARCHIVE_BYTES = 1_000_000
MAX_JSON_BYTES = 250_000


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        if urllib.parse.urlparse(newurl).scheme != 'https':
            raise ValueError('Refusing non-HTTPS artifact redirect')
        redirected = super().redirect_request(request, fp, code, message, headers, newurl)
        if urllib.parse.urlparse(request.full_url).netloc != urllib.parse.urlparse(newurl).netloc:
            redirected.remove_header('Authorization')
        return redirected


class GitHub:
    def __init__(self, token, repository):
        self.repository = repository
        self.prefix = 'https://api.github.com/repos/' + repository
        self.token = token

    def request(self, path, method='GET', data=None, binary=False):
        url = self.prefix + path
        request = urllib.request.Request(url, method=method,
                    data=None if data is None else json.dumps(data).encode(),
                    headers={'Authorization': 'Bearer ' + self.token,
                             'Accept': 'application/vnd.github+json',
                             'X-GitHub-Api-Version': '2022-11-28',
                             'Content-Type': 'application/json'})
        with urllib.request.build_opener(SafeRedirect()).open(request, timeout=60) as response:
            content = response.read(MAX_ARCHIVE_BYTES + 1 if binary else 4_000_000)
        if binary:
            if len(content) > MAX_ARCHIVE_BYTES:
                raise ValueError('Oversized artifact archive')
            return content
        return json.loads(content) if content else None

    def pages(self, path, key=None):
        for page in range(1, 101):
            separator = '&' if '?' in path else '?'
            data = self.request(path + separator + f'per_page=100&page={page}')
            items = data[key] if key else data
            yield from items
            if len(items) < 100:
                return
        raise ValueError('API pagination limit exceeded')


def read_summary(archive, java, candidate):
    with zipfile.ZipFile(io.BytesIO(archive)) as package:
        entries = package.infolist()
        if len(entries) != 1 or entries[0].filename != 'summary.json' or entries[0].file_size > MAX_JSON_BYTES:
            raise ValueError('Expected one bounded summary.json artifact')
        data = json.loads(package.read(entries[0]))
    validate(data)
    if data['metadata']['java'] != java or data['metadata']['candidate_sha'] != candidate:
        raise ValueError('Artifact does not match triggering run')
    return data


def current_pull_request(api, run, base):
    # Derive association from GitHub, not a PR number supplied by an artifact.
    if run['event'] != 'pull_request':
        return None
    candidates = list(api.pages('/commits/' + run['head_sha'] + '/pulls'))
    matching = []
    for candidate in candidates:
        pr = api.request('/pulls/' + str(int(candidate['number'])))
        if (pr['state'] == 'open' and pr['base']['repo']['full_name'] == api.repository
                and pr['head']['sha'] == run['head_sha'] and pr['base']['sha'] == base):
            matching.append(pr)
    # Ambiguity or a newer PR revision must not update an unrelated/current report.
    return matching[0] if len(matching) == 1 else None


def publish(api, run_id):
    run = api.request('/actions/runs/' + str(run_id))
    workflow = api.request('/actions/workflows/' + str(run['workflow_id']))
    if (workflow['path'] != '.github/workflows/performance.yml'
            or run['event'] not in ('pull_request', 'push', 'workflow_dispatch')
            or run['status'] != 'completed' or not SHA.fullmatch(run['head_sha'])):
        raise ValueError('Unexpected triggering workflow')
    run_url = f'https://github.com/{api.repository}/actions/runs/{run_id}'
    body = [MARKER, '## Performance comparison', '', f'[Workflow and raw JMH artifacts]({run_url})', f"Commit: {run['head_sha']}", '']
    summaries = []
    error = None
    try:
        artifacts = list(api.pages('/actions/runs/' + str(run_id) + '/artifacts', 'artifacts'))
        for java in ('21', '25'):
            selected = [a for a in artifacts if a['name'] == 'performance-summary-java' + java and not a['expired']]
            if len(selected) != 1:
                raise ValueError('Missing or ambiguous Java ' + java + ' summary')
            artifact = selected[0]
            if artifact['size_in_bytes'] > MAX_ARCHIVE_BYTES:
                raise ValueError('Oversized summary artifact')
            archive = api.request('/actions/artifacts/' + str(int(artifact['id'])) + '/zip', binary=True)
            summaries.append(read_summary(archive, java, run['head_sha']))
        if len({(s['metadata']['base_sha'], s['metadata']['harness_sha']) for s in summaries}) != 1:
            raise ValueError('Matrix jobs compared different sources')
    except (ValueError, KeyError, TypeError, zipfile.BadZipFile) as problem:
        # Never interpolate arbitrary artifact strings into a privileged comment.
        print('Incomplete/invalid performance data:', type(problem).__name__)
        error = 'Performance measurements are incomplete or invalid. Inspect the workflow logs; this is not a passing performance result.'
    if error:
        body += [error]
    else:
        body += [render(s) for s in summaries]
    if run['conclusion'] != 'success':
        body += ['', 'The measurement workflow did not succeed; results may be incomplete.']
    text = '\n'.join(body)
    conclusion = 'failure' if error or run['conclusion'] != 'success' else 'neutral'
    # Neutral reports intentionally do not turn noisy wall-clock changes into a gate.
    check = {'name': 'Performance report', 'head_sha': run['head_sha'], 'status': 'completed',
             'conclusion': conclusion, 'details_url': run_url, 'external_id': 'performance-' + str(run_id),
             'output': {'title': 'JMH comparison (Java 21 and 25)', 'summary': text}}
    existing = [c for c in api.pages('/commits/' + run['head_sha'] + '/check-runs?check_name=Performance%20report', 'check_runs')
                if c.get('external_id') == check['external_id']]
    if existing:
        del check['head_sha']
        api.request('/check-runs/' + str(existing[0]['id']), 'PATCH', check)
    else:
        api.request('/check-runs', 'POST', check)
    baseline = summaries[0]['metadata']['base_sha'] if not error else None
    if error:
        linked = [p for p in run.get('pull_requests', []) if p['head']['sha'] == run['head_sha']]
        if len(linked) == 1 and SHA.fullmatch(linked[0]['base']['sha']):
            baseline = linked[0]['base']['sha']
    if baseline:
        pr = current_pull_request(api, run, baseline)
        if pr:
            path = '/issues/' + str(pr['number']) + '/comments'
            comments = [c for c in api.pages(path) if c['user']['login'] == 'github-actions[bot]'
                        and c.get('body', '').startswith(MARKER)]
            # Recheck immediately before writing to avoid replacing a newer report.
            latest = api.request('/pulls/' + str(pr['number']))
            if latest['head']['sha'] == run['head_sha'] and latest['base']['sha'] == baseline:
                if comments:
                    api.request('/issues/comments/' + str(comments[0]['id']), 'PATCH', {'body': text})
                else:
                    api.request(path, 'POST', {'body': text})
    return text


if __name__ == '__main__':
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    api = GitHub(os.environ['GITHUB_TOKEN'], os.environ['GITHUB_REPOSITORY'])
    result = publish(api, int(event['workflow_run']['id']))
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
        stream.write(result)
