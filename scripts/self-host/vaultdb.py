"""Run SQL against the DeepSQL vault DB from a self-host script.

`sudo -u postgres psql` only works on a bare-metal install. `install.sh` produces
a Compose deployment where Postgres is a container and the host has no postgres
role — often no psql at all — so scripts that hardcode that command fail on the
topology they are meant to verify. Resolve the path once, here.
"""
from __future__ import annotations

import shutil
import subprocess


def _host_psql(args: list[str]) -> list[str]:
    return ["sudo", "-u", "postgres", "psql", "-d", "dba_agent", *args]


def _container_psql(container: str, args: list[str]) -> list[str]:
    return ["docker", "exec", "-i", container, "psql", "-U", "postgres", "-d", "dba_agent", *args]


def postgres_container() -> str | None:
    """Name of the running Compose postgres container, whatever the project is."""
    out = subprocess.run(
        ["docker", "ps", "--filter", "label=com.docker.compose.service=postgres",
         "--format", "{{.Names}}"],
        capture_output=True, text=True, check=False,
    )
    names = [n for n in out.stdout.split() if n]
    return names[0] if names else None


def _command(args: list[str]) -> list[str]:
    if shutil.which("psql"):
        probe = subprocess.run(_host_psql(["-At", "-c", "SELECT 1"]),
                               capture_output=True, text=True, check=False)
        if probe.returncode == 0:
            return _host_psql(args)
    container = postgres_container()
    if not container:
        raise RuntimeError(
            "Cannot reach the vault DB: no usable host psql and no running "
            "Compose postgres container."
        )
    return _container_psql(container, args)


def query(sql: str) -> str:
    """Run SQL and return stdout, raising on failure."""
    return subprocess.check_output(_command(["-At", "-c", sql]), text=True).strip()


def execute(sql: str) -> None:
    """Run SQL for effect, raising on failure."""
    subprocess.check_call(_command(["-v", "ON_ERROR_STOP=1", "-c", sql]))
