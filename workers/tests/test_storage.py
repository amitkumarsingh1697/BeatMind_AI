import pytest
from botocore.exceptions import ClientError

from beatmind_workers.storage import ObjectStore


class FakeS3:
    """Lists no buckets but refuses to create one, as some S3 servers do for an existing bucket."""

    def __init__(self, code):
        self.code = code

    def list_buckets(self):
        return {"Buckets": []}

    def create_bucket(self, Bucket):  # noqa: N803 - boto3 argument name
        raise ClientError({"Error": {"Code": self.code, "Message": "x"}}, "CreateBucket")


def store_with(code):
    store = ObjectStore.__new__(ObjectStore)
    store.bucket = "beatmind-audio"
    store.client = FakeS3(code)
    return store


@pytest.mark.parametrize("code", ["BucketAlreadyOwnedByYou", "BucketAlreadyExists"])
def test_existing_bucket_does_not_crash_the_worker(code):
    store_with(code).ensure_bucket()


def test_other_storage_errors_still_raise():
    with pytest.raises(ClientError):
        store_with("AccessDenied").ensure_bucket()
