from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
import textwrap
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
DOCKERFILE = ROOT / "e2b" / "mishell.Dockerfile"
BUILD_CONTEXT = ROOT / "e2b"
IMAGE_TAG = "mishell-mcp-e2b-playwright-test:latest"


def _run(cmd: list[str], *, timeout_s: int = 1200) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        cmd,
        cwd=ROOT,
        text=True,
        capture_output=True,
        timeout=timeout_s,
        check=False,
    )


@pytest.mark.skipif(
    os.getenv("RUN_E2B_TEMPLATE_TESTS") != "1",
    reason="Set RUN_E2B_TEMPLATE_TESTS=1 to run Docker-backed E2B template integration tests.",
)
def test_e2b_template_includes_playwright_cli_and_can_scrape() -> None:
    if shutil.which("docker") is None:
        pytest.skip("docker is required for E2B template integration tests")

    build = _run(
        [
            "docker",
            "build",
            "--file",
            str(DOCKERFILE),
            "--tag",
            IMAGE_TAG,
            str(BUILD_CONTEXT),
        ],
        timeout_s=2400,
    )
    assert build.returncode == 0, build.stderr

    cli = _run(["docker", "run", "--rm", IMAGE_TAG, "playwright-cli", "--version"])
    assert cli.returncode == 0, cli.stderr
    assert cli.stdout.strip(), "playwright-cli did not report a version"

    script = textwrap.dedent(
        """
        const http = require('node:http');
        const { chromium } = require('playwright');

        (async () => {
          const html = '<!doctype html><html><head><title>Mishell Playwright Test</title></head><body><h1>Sandbox Scrape OK</h1></body></html>';
          const server = http.createServer((_req, res) => {
            res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
            res.end(html);
          });
          await new Promise((resolve) => server.listen(4173, '127.0.0.1', resolve));

          const browser = await chromium.launch({ headless: true });
          const page = await browser.newPage();
          await page.goto('http://127.0.0.1:4173', { waitUntil: 'domcontentloaded', timeout: 30000 });
          const title = await page.title();
          const heading = (await page.textContent('h1'))?.trim() || '';
          console.log(JSON.stringify({ title, heading }));
          await browser.close();
          await new Promise((resolve) => server.close(resolve));
        })().catch((err) => {
          console.error(err);
          process.exit(1);
        });
        """
    ).strip()

    with tempfile.TemporaryDirectory() as tmpdir:
        script_path = Path(tmpdir) / "scrape.js"
        script_path.write_text(script, encoding="utf-8")
        scrape = _run(
            [
                "docker",
                "run",
                "--rm",
                "--volume",
                f"{tmpdir}:/tmp/test:ro",
                IMAGE_TAG,
                "bash",
                "-lc",
                "playwright install chromium && NODE_PATH=$(npm root -g) node /tmp/test/scrape.js",
            ],
            timeout_s=1200,
        )

    assert scrape.returncode == 0, scrape.stderr

    payload = json.loads(scrape.stdout.strip().splitlines()[-1])
    assert payload["title"] == "Mishell Playwright Test"
    assert payload["heading"] == "Sandbox Scrape OK"
