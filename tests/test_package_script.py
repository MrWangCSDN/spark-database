"""Release packaging regressions with isolated build outputs and a fake Maven."""
import io
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]


class PackageScriptTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='bd package script ')
        self.home = Path(self.tmp.name)
        for name in ['bin', 'config', 'sql', 'tools', 'target',
                     'compare-service/target', 'compare-spark/target/jdbc']:
            (self.home / name).mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / 'bin/package.sh', self.home / 'bin/package.sh')
        for dialect in ['new', 'gdb']:
            name = 'sql/add_bcomp_max_partition_num_' + dialect + '.sql'
            shutil.copy2(ROOT / name, self.home / name)
        for name in ['bin/start.sh', 'bin/runtime.sh', 'bin/encrypt-password.sh',
                     'bin/check-driver.sh', 'bin/CheckJdbcDriver.java', 'start.sh',
                     'stop.sh', 'config/application-local.properties.example',
                     'config/application-gdb.properties.example', 'config/bd.env.example',
                     'README.md', 'sql/test-placeholder.sql']:
            (self.home / name).write_text('# package test placeholder\n')
        self.write_jar('compare-service', 'old-service')
        self.write_jar('compare-spark', 'old-spark')
        with zipfile.ZipFile(self.home / 'compare-spark/target/jdbc/test-driver.jar', 'w') as jar:
            jar.writestr('driver-marker.txt', 'test-only-driver')
        self.archive = self.home / 'target/tss-compare-1.0.0-SNAPSHOT.tar.gz'
        self.previous_archive = b'previous release archive must survive a failed build'
        self.archive.write_bytes(self.previous_archive)
        maven = self.home / 'tools/mvn'
        maven.write_text('''#!/usr/bin/env python3
from pathlib import Path
import sys
import zipfile

arguments = sys.argv[1:]
Path('maven-arguments.txt').write_text('\\n'.join(arguments))
if '-Dpackage.test.fail=true' in arguments:
    sys.exit(9)
projects = {'compare-service', 'compare-spark'}
if '-pl' in arguments:
    projects = set(arguments[arguments.index('-pl') + 1].split(','))
for project in projects:
    output = Path(project) / 'target' / (project + '-1.0.0-SNAPSHOT.jar')
    with zipfile.ZipFile(output, 'w') as jar:
        jar.writestr('build-marker.txt', 'new:' + project)
        if project == 'compare-service':
            jar.writestr('BOOT-INF/classes/static/index.html', '<html>new frontend</html>')
''')
        maven.chmod(0o755)

    def tearDown(self):
        self.tmp.cleanup()

    def write_jar(self, project, marker):
        output = self.home / project / 'target' / (project + '-1.0.0-SNAPSHOT.jar')
        with zipfile.ZipFile(output, 'w') as jar:
            jar.writestr('build-marker.txt', marker)
            if project == 'compare-service':
                jar.writestr('BOOT-INF/classes/static/index.html', '<html>old frontend</html>')

    def run_package(self, *arguments):
        return subprocess.run(
            ['bash', str(self.home / 'bin/package.sh'), *arguments],
            cwd=self.home, capture_output=True, text=True, errors='replace', timeout=15,
            env={**os.environ, 'PATH': str(self.home / 'tools') + os.pathsep + os.environ['PATH']},
        )

    def test_service_only_build_rejects_stale_spark_jar(self):
        result = self.run_package('-pl', 'compare-service', '-DskipTests')

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn('compare-service', result.stderr)
        self.assertIn('compare-spark', result.stderr)
        self.assertEqual(self.archive.read_bytes(), self.previous_archive)

    def test_spark_only_build_rejects_stale_service_jar(self):
        result = self.run_package('-pl', 'compare-spark')

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn('compare-service', result.stderr)
        self.assertIn('compare-spark', result.stderr)
        self.assertEqual(self.archive.read_bytes(), self.previous_archive)

    def test_complete_build_publishes_fresh_modules_and_keeps_maven_options(self):
        arguments = ['-pl', 'compare-service,compare-spark', '-DskipTests',
                     '-Dskip.installnodenpm=true', '-Dskip.npm=true']
        result = self.run_package(*arguments)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.home / 'maven-arguments.txt').read_text().splitlines(),
                         arguments + ['verify'])
        with tarfile.open(self.archive) as bundle:
            for dialect in ['new', 'gdb']:
                name = 'sql/add_bcomp_max_partition_num_' + dialect + '.sql'
                self.assertEqual(bundle.extractfile('tss-compare/' + name).read(),
                                 (ROOT / name).read_bytes())
            for folder, project in [('lib', 'compare-service'), ('jobs', 'compare-spark')]:
                jar_bytes = bundle.extractfile('tss-compare/' + folder + '/' + project +
                                               '-1.0.0-SNAPSHOT.jar').read()
                with zipfile.ZipFile(io.BytesIO(jar_bytes)) as jar:
                    self.assertEqual(jar.read('build-marker.txt').decode(), 'new:' + project)

    def test_failed_build_preserves_existing_release_archive(self):
        result = self.run_package('-pl', 'compare-service,compare-spark', '-Dpackage.test.fail=true')

        self.assertEqual(result.returncode, 9, result.stderr)
        self.assertEqual(self.archive.read_bytes(), self.previous_archive)


if __name__ == '__main__':
    unittest.main()
