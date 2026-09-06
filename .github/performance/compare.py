#!/usr/bin/env python3
"""Build two immutable revisions, then compare JMH on this machine/JDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tarfile
import tempfile

from report import METHODS, SMOKE_METHODS, PREFIX, compare, render, validate


def output(command, cwd=None):
    return subprocess.check_output(command, cwd=cwd, text=True).strip()


def revision(repo, ref):
    return output(['git', 'rev-parse', '--verify', '--end-of-options', ref + '^{commit}'], repo)


def export(repo, sha, destination):
    destination.mkdir()
    archive = destination.parent / (destination.name + '.tar')
    with archive.open('wb') as stream:
        subprocess.run(['git', 'archive', '--format=tar', sha], cwd=repo, stdout=stream, check=True)
    with tarfile.open(archive) as source:
        # Reject archive entries escaping the exported checkout, including symlinks.
        source.extractall(destination, filter='data')
    archive.unlink()


def run_logged(command, log, cwd=None):
    with log.open('w') as stream:
        try:
            subprocess.run(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT, check=True)
        except subprocess.CalledProcessError:
            print(log.read_text()[-8000:], flush=True)
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True, help='Immutable SHA or local ref')
    parser.add_argument('--candidate', default='HEAD')
    parser.add_argument('--output', required=True)
    parser.add_argument('--java', required=True, choices=['21', '25'])
    parser.add_argument('--smoke', action='store_true', help='Build both revisions; run only 3 short cases')
    args = parser.parse_args()
    repo = Path(output(['git', 'rev-parse', '--show-toplevel']))
    dest = Path(args.output).resolve()
    dest.mkdir(parents=True, exist_ok=False)
    java_home = Path(os.environ['JAVA_HOME']).resolve()
    java = str(java_home / 'bin/java')
    javac = output([str(java_home / 'bin/javac'), '-version'])
    if not javac.startswith('javac ' + args.java + '.'):
        raise ValueError('JAVA_HOME does not match requested Java version')
    base, candidate = revision(repo, args.base), revision(repo, args.candidate)
    harness = output(['git', 'rev-parse', candidate + ':buff-json-benchmarks/src'], repo)
    methods = SMOKE_METHODS if args.smoke else METHODS
    metadata = {'base_sha': base, 'candidate_sha': candidate, 'harness_sha': harness, 'java': args.java,
                'compiler': javac, 'os': platform.platform(), 'architecture': platform.machine(),
                'cpu': platform.processor(), 'runner_image': os.environ.get('ImageVersion', 'local'),
                'run_id': os.environ.get('GITHUB_RUN_ID', 'local')}
    if Path('/proc/cpuinfo').exists():
        metadata['cpu'] = next((line.split(':', 1)[1].strip() for line in Path('/proc/cpuinfo').read_text().splitlines()
                                if line.startswith('model name')), metadata['cpu'])
    (dest / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    all_results = {'base': [], 'candidate': []}
    with tempfile.TemporaryDirectory(prefix='buff-json-performance-') as work:
        work = Path(work)
        trees = {name: work / name for name in all_results}
        for name, sha in (('base', base), ('candidate', candidate)):
            export(repo, sha, trees[name])
        # Compare identical workload definitions, including generated proto fixtures.
        # Each revision still uses its OWN runtime, protoc plugin and dependency versions.
        source_path = 'buff-json-benchmarks/src'
        shutil.rmtree(trees['base'] / source_path)
        shutil.copytree(trees['candidate'] / source_path, trees['base'] / source_path)
        jars = {}
        for name, tree in trees.items():
            print('BUILD', name, flush=True)
            cache_root = Path(os.environ.get('PERFORMANCE_MAVEN_REPO', str(work / 'maven')))
            local_repo = cache_root / name
            local_repo.mkdir(parents=True, exist_ok=True)
            # Cached third-party dependencies are reusable; our SNAPSHOT artifacts
            # must always come from the exact revision being built.
            shutil.rmtree(local_repo / 'io/github/suboptimal-solutions', ignore_errors=True)
            command = ['mvn', '-B', '-ntp', 'clean', 'package', '-DskipTests', '-Dspotless.skip=true',
                       '-Dmaven.repo.local=' + str(local_repo)]
            run_logged(command, dest / (name + '-build.log'), tree)
            jars[name] = tree / 'buff-json-benchmarks/target/benchmarks.jar'
            metadata[name + '_jar_sha256'] = hashlib.sha256(jars[name].read_bytes()).hexdigest()
        # All builds finish before measuring. Alternate variant order per method,
        # reversing it on Java 25; both forks for a method share a single JMH run.
        for index, method in enumerate(methods):
            order = ('base', 'candidate') if (index + int(args.java == '25')) % 2 == 0 else ('candidate', 'base')
            for name in order:
                stem = dest / (name + '-' + method)
                command = [java, '-jar', str(jars[name]), '^' + re.escape(PREFIX + method) + '$',
                           '-t', '1', '-f', '1' if args.smoke else '2',
                           '-wi', '1' if args.smoke else '3', '-i', '3' if args.smoke else '4',
                           '-w', '200ms' if args.smoke else '1s', '-r', '200ms' if args.smoke else '1s',
                           '-prof', 'gc', '-foe', 'true', '-jvmArgs', '-Xms256m -Xmx256m',
                           '-rf', 'json', '-rff', str(stem) + '.json']
                print('MEASURE', method, name, flush=True)
                run_logged(command, Path(str(stem) + '.log'))
                measured = json.loads(Path(str(stem) + '.json').read_text())
                if len(measured) != 1:
                    raise ValueError('Expected exactly one completed benchmark')
                all_results[name].extend(measured)
    for name, results in all_results.items():
        (dest / (name + '.json')).write_text(json.dumps(results, indent=2) + '\n')
    summary = compare(all_results['base'], all_results['candidate'], metadata, methods)
    validate(summary, full=not args.smoke)
    (dest / 'summary.json').write_text(json.dumps(summary, indent=2, allow_nan=False) + '\n')
    (dest / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    report = render(summary)
    (dest / 'report.md').write_text(report)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
            stream.write(report)
    print(report, flush=True)


if __name__ == '__main__':
    main()
