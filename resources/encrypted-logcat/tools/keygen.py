#!/usr/bin/env python3
"""
Generate the RSA keypair — no Kotlin or JDK required.

    pip install cryptography
    python keygen.py

Produces two files in the current folder:
    public_key.pem   -> copy into the Android app's assets/   (can only LOCK)
    private_key.pem  -> keep ONLY on this PC, next to the server (can UNLOCK)

Formats match both the Kotlin writer and the Python server:
    public  = X.509 SubjectPublicKeyInfo  ("-----BEGIN PUBLIC KEY-----")
    private = PKCS#8                       ("-----BEGIN PRIVATE KEY-----")
"""
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

key = rsa.generate_private_key(public_exponent=65537, key_size=3072)

with open("private_key.pem", "wb") as f:
    f.write(key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ))

with open("public_key.pem", "wb") as f:
    f.write(key.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    ))

print("Wrote public_key.pem  -> put in the Android app's assets/")
print("Wrote private_key.pem -> keep ONLY on this PC. Never ship it to the device.")
