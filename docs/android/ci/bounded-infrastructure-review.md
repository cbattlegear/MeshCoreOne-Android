# Bounded infrastructure verification

The native publisher recognizes the `wp003-ci-reader-route-v1` scope only after
the route has been independently admitted to trusted default-branch code. This
scope is a narrow alternative to the Android-scaffold repair contract, not a
generic exemption for changed CI readers and not full WP-003 verification.

The scope binds WP-003, repository, pinned source, manifest, policy revision,
base and head, and the complete changed-path set. Its finite path map assigns
workflow changes to `trusted-workflows`, controller/tests/docs to WP-owned
capabilities, and oracle validators/tests to `traceability-validation`. Unknown
paths and operations fail closed. The declaration fixes the Android CI workflow,
the controller-and-policy job, its controller test step, and all four test roots:
controller tests, local tests, Android parity tests, and oracle tests.
The workflow validator admits this exact four-root command in addition to its
existing commands. It still rejects duplicate or unknown roots, missing event
bindings, ignored failures and multiple controller invocations. Its validator
and regression-test paths are explicitly included in the finite path map.

The publisher independently stages the exact candidate diff, requires the
authenticated exact-head read-only source review and WP-003's existing human
acceptance gate, then checks the actual `android-ci` run and its original
controller/oracle job log. The run, attempt, workflow, application, source
review and receipt are bound through the existing authenticated publisher
workflow. Positive discovery and complete, non-skipped test outcomes are
required; retained duplicate CI bundles are not accepted. The controller log
and success-shaped candidate output are evidence inputs, not authority by
themselves: the exact-candidate source review must independently inspect all
changed classifiers, assertions, tests and workflow wiring.

Source receipt schema 4 uses the existing
[coordinator-attestation policy](README.md#native-authenticated-review-publication-bounded-wp-003-repair),
not a new reviewer identity model. An authorized coordinator performs or arranges
the actual isolated, trusted-profile read/search-only review and records its
complete decision as a GitHub-authenticated `COMMENTED` review. The coordinator's
account may also be the PR author; matching logins do not prove producer
self-review. The producer must not mint its own trusted verdict. GitHub does not
independently measure reviewer isolation, and a unit-test fixture cannot prove
that an operational review took place.

The bounded route uses the same authenticated server receipt, full trusted-base
staging digest and exact review bindings as the existing routes. A plain PASS,
unregistered candidate author, bot, self-declared profile or local staged bundle
is not an attestation. Changes to receipt content after publication invalidate
the publisher's receipt-body digest. No separate reviewer App or second human
`APPROVED` review is required. Actual independent review and a separate trusted
bootstrap decision are still necessary; local tests cannot supply either.

After trusted-base admission only, the reviewer can create review input with
the data-only command:

```text
python tools/android-port/controller/native_review.py stage-infrastructure --repo <repo> --trusted-base <exact-base-sha> --wp WP-003 --pr <number> --scope <absolute-scope.json> --output <absolute-private-output.json>
```

It requires a ready PR on the current base, checks the exact server-side changed
paths, and returns `authoritative: false`; it does not run candidate code or
publish a check. The scope object has exactly `schema_version`, `kind`,
`scope_id`, `binding`, `paths` and `verification`. The `verification` value is
fixed by the controller; `paths` must equal the PR's complete changed-path set
and each path must map to its declared trusted capability operation.

Publication labels this result `bounded-infrastructure-verification`, explicitly
sets `wp_complete` and `dependency_unlock` to false, and cannot satisfy full-WP
completion, historical native-reader verification, merge approval or dependency
unlock. The ordinary runtime-reader guard, bounded Android repair guard, and
supervised WP-003 human gate remain in force.

## Bootstrap boundary

This route cannot attest its own first deployment. The prepared candidate is not
a trusted publisher and cannot publish checks or approve its installation. A
separate maintainer decision must authorize an exact-candidate bootstrap and
integration into trusted default-branch code. Until that occurs, the deployed
publisher does not recognize this scope. Bootstrap, publication, workflow
dispatch, protection changes, merge and deployment are separate actions and are
not authorized by preparing this contract.
