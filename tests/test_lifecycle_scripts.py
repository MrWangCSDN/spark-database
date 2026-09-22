"""Lifecycle regression checks with a harmless fake JVM (no DB or Spark access)."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

class LifecycleTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = Path(self.tmp.name)
        for name in ['bin', 'config', 'lib', 'jobs', 'drivers', 'jdk/bin', 'spark/bin']:
            (self.home / name).mkdir(parents=True, exist_ok=True)
        for name in ['start.sh', 'stop.sh', 'bin/start.sh', 'bin/runtime.sh']:
            shutil.copy2(ROOT / name, self.home / name)
        for name in ['lib/compare-service-1.0.0-SNAPSHOT.jar', 'jobs/compare-spark-1.0.0-SNAPSHOT.jar', 'drivers/gauss.jar', 'config/application-local.properties']:
            (self.home / name).touch()
        self.executable('jdk/bin/java', '''#!/usr/bin/env bash
if [[ "${1:-}" == -version ]]; then echo 'openjdk version "17.0.12"' >&2; exit 0; fi
exec python3 -u -c 'import time; print("Started CompareApplication", flush=True); time.sleep(120)' "$@"
''')
        self.executable('jdk/bin/jar', '#!/usr/bin/env bash\necho org/opengauss/Driver.class\n')
        self.executable('spark/bin/spark-submit', '#!/usr/bin/env bash\nexit 0\n')
        self.env = f'''JAVA_HOME='{self.home}/jdk'
SPARK_HOME='{self.home}/spark'
TSS_METADATA_DRIVER='org.opengauss.Driver'
TSS_METADATA_URL='jdbc:opengauss://example/db'
TSS_METADATA_PASSWORD='test-only'
BD_START_WAIT_SECONDS=3
BD_STOP_TIMEOUT_SECONDS=3
'''
        (self.home / 'config/bd.env').write_text(self.env)

    def executable(self, name, body):
        p = self.home / name
        p.write_text(body)
        p.chmod(0o755)

    def run_script(self, name):
        return subprocess.run(['bash', str(self.home / name)], capture_output=True, text=True, errors="replace", timeout=10)

    def tearDown(self):
        self.run_script('stop.sh')
        self.tmp.cleanup()

    def test_start_duplicate_and_graceful_stop(self):
        r = self.run_script('start.sh')
        self.assertEqual(r.returncode, 0, r.stderr)
        pid = (self.home / 'bd.pid').read_text()
        self.assertEqual(self.run_script('start.sh').returncode, 0)
        self.assertEqual((self.home / 'bd.pid').read_text(), pid)
        self.assertEqual(self.run_script('stop.sh').returncode, 0)
        self.assertFalse((self.home / 'bd.pid').exists())
        self.assertEqual(self.run_script('stop.sh').returncode, 0)

    def test_stop_refuses_an_unrelated_pid(self):
        (self.home / 'bd.pid').write_text(str(os.getpid()))
        r = self.run_script('stop.sh')
        self.assertNotEqual(r.returncode, 0)
        self.assertIn('拒绝停止', r.stderr)
        (self.home / 'bd.pid').unlink()

    def test_placeholder_and_missing_job_fail_before_spawn(self):
        (self.home / 'config/bd.env').write_text(self.env + "TSS_METADATA_PASSWORD='CHANGE_ME'\n")
        self.assertNotEqual(self.run_script('start.sh').returncode, 0)
        self.assertFalse((self.home / 'bd.pid').exists())
        (self.home / 'jobs/compare-spark-1.0.0-SNAPSHOT.jar').unlink()
        self.assertNotEqual(self.run_script('start.sh').returncode, 0)

    def test_foreground_rejects_url_driver_mismatch(self):
        (self.home / 'config/bd.env').write_text(self.env + "TSS_METADATA_URL='jdbc:postgresql://example/db'\n")
        self.assertNotEqual(self.run_script('bin/start.sh').returncode, 0)

if __name__ == '__main__':
    unittest.main()
