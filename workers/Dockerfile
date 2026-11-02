# CPU image for the stub runner and post-processing (watermark, AI label, C2PA, encoding).
FROM python:3.12-slim

# ffmpeg encodes the labelled MP3 and WAV files (finishing.py).
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --uid 10001 worker
WORKDIR /app
COPY pyproject.toml ./
COPY beatmind_workers ./beatmind_workers
RUN pip install --no-cache-dir .

USER worker
CMD ["beatmind-worker"]
