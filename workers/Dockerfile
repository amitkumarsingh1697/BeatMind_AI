# CPU image for the stub runner and post-processing. The GPU image (ACE-Step) is added in Sprint 1.
FROM python:3.12-slim

RUN useradd --create-home --uid 10001 worker
WORKDIR /app
COPY pyproject.toml ./
COPY beatmind_workers ./beatmind_workers
RUN pip install --no-cache-dir .

USER worker
CMD ["beatmind-worker"]
