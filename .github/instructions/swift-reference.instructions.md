---
applyTo: "MC1/**,MC1Services/**,MC1Tests/**,MC1Widgets/**,MeshCore/**,Shared/**,AppIcon.icon/**,LICENSE,project.yml,swiftgen.yml"
---

# Read-only Swift reference

These paths are specification inputs, not Android write targets. Read their current behavior and
associated tests/resources at `db14559b39d32322b06477c6ae676112f583db50`; do not edit, regenerate,
reformat, translate, update goldens, install packages into or advance this tree during port work.

The manifest maps each reference file to the WP that ports it, plus cross-references, including
errors/extensions/helpers, widget localization/plurals, themes/icons/styles and license inputs. An explicit file exclusion removes
Apple glue/generated output/billing, not associated required behavior. The user-approved message
translation removal has exact `removed-translation` source/test/helper exclusions; mixed messaging,
localization, backup and rendering inputs remain in scope. No future build requires translation.

Use the Swift/macOS oracles in CI for backup compatibility. If a needed source is missing from the manifest
inventory, or the reference looks changed, tell the maintainer; do not infer that a header or filename proves
acceptance.
