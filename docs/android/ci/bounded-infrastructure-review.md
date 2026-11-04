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
the controller-and-policy job, its controller test step, and four mandatory test roots:
controller tests, local tests, Android parity tests, and oracle tests.
The workflow validator admits this exact four-root command in addition to its
existing commands. It still rejects duplicate or unknown roots, missing event
bindings, ignored failures and multiple controller invocations. Its validator
and regression-test paths are explicitly included in the finite path map.
The accessibility-test root is additionally mandatory when the shared native
suite selector selects it from the exact immutable base/head and changed paths.
Acceptance uses that same selection logic without consulting the local checkout;
a removed selected suite, missing/zero selected root or unknown extra root fails.
Production readers and regression tests are classified by their declared
operations, not filename substrings. The scope's admitted path operations are
passed unchanged through data-only staging and receipt revalidation, including
oracle paths outside WP-003's ordinary manifest write paths.

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

## Compiler-action authority is not provided by this route

Inspection of trusted main `43341865f923d439ace1f22edac81f7fb642355c`
found no independently controlled compiler-action verifier. The Android CI job
checks out the candidate and executes its Python launcher and Gradle wrapper,
settings, build logic and plugins. Wrapper/dependency checksums, environment
allowlisting and committed-input comparison establish selected input identity,
not independent control of compiler actions. Candidate-authored task observers,
source hashes, output files, task types and successful process exits cannot prove
that a compiler performed the declared work. Moving only the observer into a
trusted init script would still permit candidate build configuration to replace
compiler actions in that Gradle process.

The default-branch publisher is independently controlled but deliberately
data-only: it fetches candidate Git objects, revalidates the authenticated source
review and checks the original CI job/log. It does not execute a separate
compiler or provide an isolated observer of compiler execution. Its successful
publication therefore cannot be repurposed as compiler proof. The other
controller workflows are read-only previews/audits, not an execution route.
An active Actions workflow catalog entry is not evidence that the file exists
in the current trusted tree or that a verifier was deployed.

Source-authoring admission and publication admission remain different. At that
trusted revision, WP-003 admits `ci.py` with `wp-owned/modify` and `test_ci.py`
with `wp-owned/test`; `wp003-ci-reader-route-v1` refuses both with
`Infrastructure scope has unknown, missing, duplicate or untested paths`.
The complete #518 successor also contains six other paths absent from the finite
map: the CI README, WP-003 deviation, `local_scope.py`,
`verify_candidate_task_graph.gradle`, `test_ci_environment.py` and
`test_local_scope.py`. Omitting them from the declared scope is not a workaround.
A new `.github/workflows/android-compiler-verification.yml` can be authored under
`trusted-workflows/update-directly-required-job-or-input`, but this finite
publication route rejects it too. No new workflow or compiler verifier has been
created, deployed or dispatched by this investigation.

The supported alternative is a separately reviewed, explicitly authorized
exact-candidate bootstrap into trusted default-branch infrastructure, as required
by the bootstrap boundary above. Before positive compiler acceptance, that
infrastructure needs a trusted executable-input closure and compiler invocation
whose actions cannot be replaced by candidate configuration, a separate
credential-free candidate execution boundary, and evidence bound to the exact
source/input/toolchain and actual run/attempt. An authenticated controller must
validate the result in both live and historical readers. Its finite publication
path map must first admit the actual implementation/test/workflow surfaces;
the candidate cannot authorize that expansion or mint its own receipt.
Any new check must remain non-required until it has passed end to end on a
representative real PR. Do not change protection or treat a unit-test fixture,
workflow dispatch or source-review publication as that operational proof.
