"""Object storage (ARCH-08). Cloudflare R2 in the cloud, MinIO locally; both speak the S3 API."""

from __future__ import annotations

import os

import boto3


class ObjectStore:
    def __init__(self) -> None:
        self.bucket = os.environ.get("S3_BUCKET", "beatmind-audio")
        self.client = boto3.client(
            "s3",
            endpoint_url=os.environ.get("S3_ENDPOINT"),
            aws_access_key_id=os.environ.get("S3_ACCESS_KEY"),
            aws_secret_access_key=os.environ.get("S3_SECRET_KEY"),
            region_name=os.environ.get("S3_REGION", "auto"),
        )

    def ensure_bucket(self) -> None:
        """Creates the bucket if missing. Local development only; cloud buckets come from OpenTofu."""
        existing = {b["Name"] for b in self.client.list_buckets().get("Buckets", [])}
        if self.bucket not in existing:
            self.client.create_bucket(Bucket=self.bucket)

    def put(self, key: str, data: bytes, content_type: str) -> str:
        self.client.put_object(Bucket=self.bucket, Key=key, Body=data, ContentType=content_type)
        return key
