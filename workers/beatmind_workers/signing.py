"""C2PA content credentials (INT-07, REQ-F-30, COMP-IN-02).

Production signs with BeatMind's certificate and a key held in a managed KMS (REQ-SEC-07); buying
the certificate and setting up the key are release tasks in TODO.md. Until then, set
``C2PA_SIGN_CERT`` and ``C2PA_SIGN_KEY`` to PEM files, or leave them unset and the worker makes a
throwaway development certificate authority at start-up. Manifests signed that way are valid but
show as "untrusted signer" in C2PA tools, which is what we want outside production.
"""

from __future__ import annotations

import datetime
import io
import json
import logging
import os
from typing import Any

import c2pa
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

log = logging.getLogger("beatmind.signing")

# IPTC term for media made by a trained model (C2PA "digital source type").
TRAINED_ALGORITHMIC_MEDIA = "http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"
MIME = {"mp3": "audio/mpeg", "wav": "audio/wav"}


def _name(common_name: str) -> x509.Name:
    return x509.Name(
        [
            x509.NameAttribute(NameOID.COMMON_NAME, common_name),
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, "BeatMind AI (development)"),
        ]
    )


def development_credentials() -> tuple[bytes, bytes]:
    """A fresh CA and signing certificate (ES256). Returns (certificate chain PEM, key PEM)."""
    now = datetime.datetime.now(datetime.UTC)
    ca_key = ec.generate_private_key(ec.SECP256R1())
    ca = (
        x509.CertificateBuilder()
        .subject_name(_name("BeatMind development CA"))
        .issuer_name(_name("BeatMind development CA"))
        .public_key(ca_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=3650))
        .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
        .add_extension(
            x509.KeyUsage(False, False, False, False, False, True, True, False, False), critical=True
        )
        .sign(ca_key, hashes.SHA256())
    )
    key = ec.generate_private_key(ec.SECP256R1())
    leaf = (
        x509.CertificateBuilder()
        .subject_name(_name("BeatMind development signer"))
        .issuer_name(ca.subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=365))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(
            x509.KeyUsage(True, False, False, False, False, False, False, False, False), critical=True
        )
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.EMAIL_PROTECTION]), critical=False)
        .add_extension(
            x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False
        )
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False)
        .sign(ca_key, hashes.SHA256())
    )
    chain = leaf.public_bytes(serialization.Encoding.PEM) + ca.public_bytes(serialization.Encoding.PEM)
    pem = key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
    )
    return chain, pem


class ContentCredentials:
    """Signs files with a C2PA manifest saying they were made by a trained model."""

    def __init__(self, cert_chain: bytes | None = None, private_key: bytes | None = None) -> None:
        if cert_chain is None or private_key is None:
            cert_path, key_path = os.environ.get("C2PA_SIGN_CERT"), os.environ.get("C2PA_SIGN_KEY")
            if cert_path and key_path:
                with open(cert_path, "rb") as c, open(key_path, "rb") as k:
                    cert_chain, private_key = c.read(), k.read()
            else:
                log.warning("C2PA_SIGN_CERT/C2PA_SIGN_KEY not set: signing with a development certificate.")
                cert_chain, private_key = development_credentials()
        info = c2pa.C2paSignerInfo(c2pa.C2paSigningAlg.ES256, cert_chain, private_key, None)
        self._signer = c2pa.Signer.from_info(info)

    @staticmethod
    def manifest(title: str, details: dict[str, Any]) -> dict[str, Any]:
        return {
            "claim_generator_info": [{"name": "BeatMind AI", "version": "0.2"}],
            "title": title,
            "assertions": [
                {
                    "label": "c2pa.actions",
                    "data": {
                        "actions": [
                            {
                                "action": "c2pa.created",
                                "digitalSourceType": TRAINED_ALGORITHMIC_MEDIA,
                                "softwareAgent": {"name": "BeatMind AI"},
                            }
                        ]
                    },
                },
                {"label": "ai.beatmind.provenance", "data": details},
            ],
        }

    def sign(self, data: bytes, fmt: str, title: str, details: dict[str, Any]) -> bytes:
        builder = c2pa.Builder(json.dumps(self.manifest(title, details)))
        out = io.BytesIO()
        builder.sign(self._signer, MIME[fmt], io.BytesIO(data), out)
        return out.getvalue()


def read_manifest(data: bytes, fmt: str) -> tuple[dict[str, Any], str]:
    """Returns (active manifest, validation state) for a signed file."""
    reader = c2pa.Reader(MIME[fmt], io.BytesIO(data))
    store = json.loads(reader.json())
    return store["manifests"][store["active_manifest"]], reader.get_validation_state()
