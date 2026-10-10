# WP-406 translation admission preparation

Status: **BLOCKED before SDK/model admission and product implementation.**

## Assignment and binding

- Repository: `cbattlegear/MeshCoreOne-Android`; existing issue #132.
- Owner: `platform-integrations-engineer`; branch: `didactic-engine`.
- Session: `612416ad4617467fb8bacc5a1a121049`; typed reservation revision 1,
  capability `wp-owned`, operation `record-evidence`, scope this directory.
- Base/head at preparation: `4000cdcf8d57eb4696883f8793e09ea4a6219d46`.
- Read-only source: `db14559b39d32322b06477c6ae676112f583db50`.
- Manifest SHA256: `58f7ebd7f46bbe0636c71005f20776efe139a287279e4f4708b25ce6bfa3f892`.
- Policy revision: `1f5f2b54f17595b3bab93edc4f607ccb9e3b2fdd409943e32fd394ec19c3bfd4`.

On 2026-10-10 the user selected evaluation of an open-source on-device engine
and models with no telemetry or GMS requirement. This is an evaluation direction,
not legal approval of an engine, model, dataset or linked dependency.

PR #161 was authoritatively reported merged at the base above. The implementation
prerequisites have merged PRs: WP-307 #141, WP-308 #143, WP-213 #52,
WP-218 #25 and WP-317 #144. These merge records do not independently establish
formal parity acceptance. No existing WP-406 PR or reservation was found.

## Primary-source findings

The GitHub API was available for the following public repositories:

- [OPUS-MT training README at d5e932c](https://github.com/Helsinki-NLP/OPUS-MT-train/blob/d5e932c96d77596378010aa7e7f1caeb8ccaa9f8/README.md)
  declares the project's pre-trained models and linked Tatoeba models under
  CC-BY 4.0. This corrects an unverified search summary that generalized
  OPUS-MT models as noncommercial. Each actual selected model still needs
  revision, bytes, terms, attribution, training-data obligations and a human
  admission decision; the README is not approval of any particular artifact.
- [Bergamot at 5ae1b1e](https://github.com/mozilla/bergamot-translator/tree/5ae1b1ebb3fa9a3eabed8a64ca6798154bd486eb)
  has GitHub repository license metadata identifying MPL-2.0. That metadata
  alone does not verify dependency/model licenses, Android integration,
  native ABI/page compatibility, runtime behavior or language coverage.

ONNX Runtime is another evaluation candidate, not a selected dependency.
Its GitHub API request was denied by the available credential's organization
SSO policy. Direct primary-source retrieval for ONNX Runtime, Google ML Kit
terms and a Hugging Face model card failed DNS resolution; a curl retry also
failed DNS resolution. No SDK-license inference from a sample-code license,
model permission inference, or unverified search claim is used for admission.

## Readiness and acceptance

The shallow checkout initially lacked the pinned reference object. Fetching
that exact commit restored it without advancing the pin. The repository
reservation helper then successfully loaded the manifest and recorded the
bounded evidence reservation.

The documented `android/scaffold/check_environment.py` was run with an
environment filtered using the existing `environment-allowlist.json`.
It returned **BLOCKED: JAVA_HOME and ANDROID_HOME/ANDROID_SDK_ROOT are required**.
No JDK, SDK, model or dependency was installed and no Android tests ran.
An initial unsanitized preflight also correctly rejected non-allowlisted
environment variables; no candidate Gradle code was executed.

The WP-406 manifest verification entry remains unconfigured, with no declared
commands. Implementation acceptance must use approved actual verification
commands and original-case inventory, not invented tasks or zero-test evidence.
All three WP-406 acceptance IDs remain unverified.

## Required next gate

Obtain accessible primary sources for a concrete open-source engine and
specific model set, then review their complete license/attribution/data terms,
supported languages, Android integration, storage/download consent, cancellation,
offline execution and absence of telemetry/GMS requirements. Human approval
must precede linking or downloading the SDK/models. Licensed pinned build
inputs and approved verification configuration are also required before
implementation validation.

There is no translation implementation, fake translation result, linked SDK,
model download, legal approval, hardware proof or release-readiness claim.
