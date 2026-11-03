# BeatMind AI

Your personal music generator and DJ mixer: original songs and beats from a vibe or your own lyrics, in your language, at a price that fits where you live.

> **Status:** Sprint 6 (international payments and stems). Songs are short test tones from a stub until the worker is pointed at ACE-Step; see [Real songs](#real-songs). Sprint reports are in [docs/09-delivery](docs/09-delivery/).

## Documentation

Non-code release tasks (accounts, keys, GPU runs, listeners, legal) are tracked in [TODO.md](TODO.md).

Start at [docs/README.md](docs/README.md) for the package index, evidence rules, ID scheme and batch plan.

| Area | Document |
| --- | --- |
| Product | [PRD v2.1](docs/02-product/prd.md) |
| Architecture | [TDD v2.0](docs/03-architecture/tdd.md) and [ADRs](docs/03-architecture/adr/) |
| Market | [Market, launch-market and competitor analysis](docs/01-market/market-and-competitor-analysis.md) |
| Governance | [Decision log](docs/00-governance/decision-log.md), [open questions](docs/00-governance/open-questions.md) |
| Registers | [Assumptions](docs/registers/assumptions.csv), [risks](docs/registers/risks.csv), [sources](docs/registers/sources.csv), [Excel workbook](docs/registers/beatmind-registers.xlsx) |

## Code

| Folder | What | Stack | Tests |
| --- | --- | --- | --- |
| [`api/`](api/) | Core API (ARCH-02): sign-in, song generation, job queue, pricing, waitlist, metrics | Java 21, Spring Boot 4.1, Spring Modulith, Flyway, PostgreSQL 16 | JUnit 5 (`mvn verify`) |
| [`workers/`](workers/) | Generation workers (ARCH-04): queue claim, CPU stub and ACE-Step runners, GPU benchmark and Hindi spike kits | Python 3.11+ | pytest, ruff |
| [`web/`](web/) | Web app (ARCH-01): waitlist page in English and Hindi; "make a song" developer preview | Next.js 16, React 19, TypeScript | `npm test`, `npm run build` |
| [`infra/`](infra/) | Staging and production on Cloudflare and Oracle Cloud; local monitoring (`infra/observability/`) | OpenTofu, Prometheus, Grafana | `tofu validate` |

### Run it locally

You need Docker. Then:

```sh
docker compose up --build
```

Then open:

- http://localhost:3000/en/create to **make a song**: sign in with any email (locally the code is filled in for you, no email is sent), enter an 18+ date of birth, describe a song and play the two takes. The worker turns each request into two short test tones, so the whole flow works without a GPU.
- http://localhost:3000/en/admin/jobs for failed jobs (sign in as `admin@example.com`).
- http://localhost:3000 for the waitlist page and http://localhost:8080/v1/pricing?country=IN for the API.

`scripts/dev/first-song-smoke.sh` checks the same flow from the command line.

Monitoring (US-004): `TRACING_EXPORT_ENABLED=true docker compose --profile observability up --build` adds Grafana at http://localhost:3001 (dashboard "BeatMind pipeline"), Prometheus at http://localhost:9090 and Jaeger traces at http://localhost:16686.

### Real songs

By default the worker makes short test tones, so the whole app runs in seconds without a music
model. For real songs, point it at an ACE-Step 1.5 server.

**On a Mac with Apple Silicon (M1 or later, 16 GB or more).** ACE-Step runs on the Mac's GPU
through Apple's MLX. It has to run natively, outside Docker, because Docker on a Mac cannot use
the GPU.

1. Install ACE-Step once (about 10 GB of models download on the first start):

   ```sh
   xcode-select --install    # git, if you don't have it
   git clone https://github.com/ace-step/ACE-Step-1.5.git && cd ACE-Step-1.5
   chmod +x start_api_server_macos.sh
   ./start_api_server_macos.sh    # serves on http://127.0.0.1:8001; leave it running
   ```

   Open http://127.0.0.1:8001/docs to check it is up.
2. In a second terminal, in this repository:

   ```sh
   BEATMIND_RUNNER=acestep ACESTEP_URL=http://host.docker.internal:8001 \
   ACESTEP_TIMEOUT_SECONDS=1800 BEATMIND_LEASE_SECONDS=2400 \
   docker compose up --build
   ```

3. Open http://localhost:3000/en/create and make a song. Each request renders two takes; the
   first one is slow while the models load.

If the worker log says the connection was refused, edit `start_api_server_macos.sh`, set
`HOST="0.0.0.0"` and an `API_KEY`, restart it, and add `ACESTEP_API_KEY=<that key>` to the command
in step 2.

**On a rented NVIDIA GPU**, start the server as in [workers/bench/README.md](workers/bench/README.md),
then `BEATMIND_RUNNER=acestep ACESTEP_URL=http://<gpu-box>:8001 ACESTEP_API_KEY=<key> docker compose up`.

**On a CPU only** it works but is slow (about 5 minutes per take on 4 cores); use the settings in
[workers/spikes/hindi/README.md](workers/spikes/hindi/README.md) and raise both timeouts as above.

`ACESTEP_TIMEOUT_SECONDS` is how long the worker waits for one request's takes, and
`BEATMIND_LEASE_SECONDS` is how long a running job may take before it counts as stuck and is
started again; keep the lease above the timeout. Stems still use the band-split stub unless the
worker runs with `BEATMIND_STEMS=demucs` and the `demucs` package.

To work on one part:

```sh
docker compose up -d postgres            # database for tests
cd api && mvn verify                     # Java tests
cd workers && pip install -e ".[dev]" && pytest && ruff check .
cd web && npm ci && npm test && npm run dev
```

CI runs all of this on every pull request, plus an RTM check, a secret scan, dependency review and a Docker Compose smoke test.
