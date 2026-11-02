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

The publisher independently stages the exact candidate diff and checks the
actual `android-ci` run and its original controller/oracle job log. A human
`COMMENTED` source receipt is only a transport envelope; it is not proof that
the coordinator's `PASS` is independent. For this bounded route, the receipt
must reference one successful `MeshCore One independent read-only review`
check-run whose GitHub App ID matches
`review_policy.trusted_read_only_reviewer_app_id` from the trusted base. The
check's authenticated output must bind the exact repository/WP/source/manifest/
policy/base/head, PR number, staged-input digest, review bytes and review
digest. Its check suite must independently match the same trusted App ID and
head. The coordinator's receipt content must exactly match the review output.
Changing the comment, staging, base, head, app, check-run or review invalidates
the binding. The PR author may post the human receipt, but that identity alone
cannot satisfy the independent-review check. This does not require a second
human `APPROVED` review.

No independent reviewer App ID is currently configured in the trusted policy,
and the existing publisher workflow does not launch a reviewer. Consequently,
the current deployment fails closed with a missing-capability error; configuring
and installing a genuinely read-only reviewer App is a separate trusted-base
bootstrap prerequisite. That App must be distinct from GitHub Actions and
restricted from source, PR, approval, dispatch, and merge writes; its check is
the only write it emits. The reviewer profile hash or a coordinator-authored
review object cannot substitute for that identity. Positive local fixtures
exercise the exact check-run binding but do not claim that such an App is
deployed or available.

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
