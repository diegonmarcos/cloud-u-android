package app.sterna.core.imap

/**
 * Real OpenPGP public keys, exported minimized and base64'd exactly the way the app stores them
 */
internal object AutocryptKeys {

    /**
     * An RSA-4096 key (sign + encrypt subkey), minimized: **1 163 octets, 1552 base64
     */
    const val RSA_4096: String =
        "mQINBGqYiFgBEACyMmikxS2dso/tpI7t2nLIowWrYKaMYyQ5q9uEWAX1YZAAv9hU6SSE5Fo6QRQJ1V/V212U1C/K8UUVdhbH" +
        "v4L/5HP+cA1ZY3/kue50VK39PslzD28g2E5P2e1RjmTWotRcAVFnZAPzcBNvVr6Q4gCp2EgLFAw1lAA3ETHY51bLSv6J5uDo" +
        "9d2IOT3Tpq3tXt555dOTjR++lxVozKeotT+LA3mZywjcbvqSotAGzKtIVJKsQMUGxvUdOz46EAuHlKqHcTm7+RO8D8/tp/tZ" +
        "w/Gz+L0ecRsJB5u5RZsa3ED78cJHJ4WaQH74MizGtlHgR9jayP2W8fybT/2eQMxsnuMjobEKw9v/PiO+i1qGAY9PsFHdbl0H" +
        "6EGFqz8eURbVPIVd7c4zwkxU4EAeIki814Z3u//U04KsYB+cE6e8VUfLs2p8oiKsUEvzQVuSnNgrREvF5dJRzu9M9csM6lfR" +
        "V1pNAqOcMf1nU7NZgX55dpKe6+yYmrXf6njsZZh4l8ggp3Px3e/RhuRuGIFuMXdQhbyAR6jqtN2WO3BFvTZipKEnqdL66dBc" +
        "nRNVtadVobSoMY/aSCUvlq1nZBcuNMjaBrJ91zxTbMl6SJQfokDWj+hLiDPw2cOjNc6qaD+JRbZSNOP227y/JV3RTqaLkZL2" +
        "p+9j3el1br22zYXUaYASLv9o9wARAQABtChBdXRvY3J5cHQgRml4dHVyZSA8Zml4dHVyZUBleGFtcGxlLnRlc3Q+iQJOBBMB" +
        "CgA4FiEELsdAE6ugOT1xxdXVE+EgoitwqSMFAmqYiFgCGw8FCwkIBwIGFQoJCAsCBBYCAwECHgECF4AACgkQE+EgoitwqSN5" +
        "PxAApGGaf3SbqSmG/iI4pjDW1rUF+v0PM6YXP0r4UE/T7UwYkLQa334VjhMP+RkYIJJYfDMYbFsz7ier8IpELMHBxGBQGknB" +
        "I41v3dYeD4PSIKeEKc0mtXghENSbZplokKp4IBu3y2b2opJaxVHmqE3YETbWdueViLr7E3H/W469mNTzT8KoEHxqT2c4mKBD" +
        "ByiEVEMIIWANeGFgI0Z0VJH1JFE7LapcQBglj5J6riSnDUvsR1jZAnPDMGwXMLbHnwpCIlazAPuiBCxvKrHiTCcULwiWJsO4" +
        "cAtt6A0rx1KCTghxEJ7Kly7Zmu0bNQAENS2WBo44sPPrQTCFbMtHsQzq/ULeNA2Tmx2YqNwp3DVyh+qcHkN1oGWTFq5NgGm0" +
        "o/o1D8dS3ColmeqOcFglGaRL1SDKWTx0PIFblI6WGyF1f4acmSOSHrj+kTFdCnShP9arlrr4EwAofJYxWFbtMI0OKmmOtyj6" +
        "px8Ld0lhGaqYh2exi9fUOyaOqxy0aL12Ybrl8V3zc+HJKKdGgS1wLQMjayFBhWwAf/9WYyo7RGyCHPJFQgyKMBtPe7pPybt2" +
        "8U0Fa2SguJOZJpqweX4yd2d7FSaPVZlpqRY+YFOEjq+AXnkwHaBJk4vUi8+sR4mkRMNRypAUtzokQsIntZjxvozvoPBnmPKg" +
        "KQ5zPFeNqM2ghNA="

    /**
     * An ed25519 key with its cv25519 encryption subkey, minimized: **417 octets, 556 base64
     */
    const val ED25519: String =
        "mDMEapiIYBYJKwYBBAHaRw8BAQdAL6yHkgUz0dB5Md2pqMoWVneaOOKidIZAO6BWV4tDQDi0JEF1dG9jcnlwdCBTaG9ydCA8" +
        "c2hvcnRAZXhhbXBsZS50ZXN0PoiQBBMWCAA4FiEEQgDOLGRAZ37+XKagKIFclpE9D94FAmqYiGACGwMFCwkIBwIGFQoJCAsC" +
        "BBYCAwECHgECF4AACgkQKIFclpE9D95NOwEAiWm5h199EwqsrhICBvLsm2ROPzkVxUzYWBHjBXiJzQ0A/i03bvR9MfyUx/9Z" +
        "9/ReevJqTFAwuChl1X8mopoWiSALuDgEapiIZxIKKwYBBAGXVQEFAQEHQCnAH25lBtWvj1vGQYHnS0ka5Tylk1wuIUd5l14b" +
        "DTchAwEIB4h4BBgWCAAgFiEEQgDOLGRAZ37+XKagKIFclpE9D94FAmqYiGcCGwwACgkQKIFclpE9D9731wEAjKqk2vqO92PR" +
        "3SSMH54FLKV/VfpJkCITPsj5sRkAzxoBAMdaNaEJ17vLUr7U6H7VcDHtKM1S0i0vsqKbFK0Kh84O"
}
