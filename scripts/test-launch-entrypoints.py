#!/usr/bin/env python3
"""Test launcher branches with a mock Maven and inert service/config functions."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parent.parent
source = (root / 'run.sh').read_text()
function = source[source.index('recompile_modules() {'):source.index('\ncase "$cmd" in')]
restart = source[source.index('    restart)\n'):source.index('    *)\n', source.index('    restart)\n'))]
with tempfile.TemporaryDirectory(prefix='herald-launch-fixture-') as directory:
    fixture = Path(directory)
    fake = fixture / 'mvnw'
    fake.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$FIXTURE_LOG"\n')
    fake.chmod(0o755)
    script = fixture / 'test.sh'
    script.write_text('set -uo pipefail\nSCRIPT_DIR="$FIXTURE_DIR"\nBOT_PORT=1\nUI_PORT=2\ncheck_env(){ :; }\nstop_module(){ :; }\n'
                      + function + '\ncase "$1" in\n' + restart + '\nesac\n')
    for module in ('bot', 'ui'):
        log = fixture / (module + '.log')
        env = dict(os.environ, FIXTURE_DIR=str(fixture), FIXTURE_LOG=str(log))
        subprocess.run(['bash', str(script), 'restart', module], env=env, check=True, capture_output=True)
        assert log.read_text().splitlines() == [f'-pl herald-{module} -am -q -DskipTests install', f'-pl herald-{module} spring-boot:run']
        log.unlink()
        # Mock identifies -am anywhere; a failed shared build must prevent launch.
        fake.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$FIXTURE_LOG"\ncase " $* " in *" -am "*) exit 9;; esac\n')
        result = subprocess.run(['bash', str(script), 'restart', module], env=env, capture_output=True)
        assert result.returncode != 0
        assert len(log.read_text().splitlines()) == 1
        fake.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$FIXTURE_LOG"\n')
    assert '\ndev:\n\t./run.sh bot\n' in (root / 'Makefile').read_text()
print('Restart bot/ui rebuild shared dependencies, fail closed on build failure, and make dev delegates to normal startup.')
