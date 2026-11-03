# Pinned TLC input

`tla2tools-2ef2af8.jar` is the unchanged existing formal formatter/parser input,
vendored so a fresh runner can prepare the same tool graph without relying on a
mutable upstream release asset. It is not a new TLC build or version upgrade.

- Bytes: `4502259`
- SHA-256: `d15655eeaff4b3f7b3d65c46b838bc0f35d131b2ef4b1e473689b62c98d4726f`
- Observed embedded upstream revision: `2ef2af8c767b76e5db5cba2878f027ac07aad36c`
- Observed build timestamp: `2026-10-24T01:36:19.357Z`
- Observed tags: `2026.10.23.135453;rolling;v1.8.0`

[Upstream source at the recorded revision](https://github.com/tlaplus/tlaplus/tree/2ef2af8c767b76e5db5cba2878f027ac07aad36c)
and its [upstream MIT license](https://github.com/tlaplus/tlaplus/blob/2ef2af8c767b76e5db5cba2878f027ac07aad36c/LICENSE)
provide provenance. The build metadata above is read from the artifact; it is not
an independent build attestation. The current mutable `v1.8.0` asset has a
different digest and is deliberately not substituted.

All upstream notices remain verbatim inside the unchanged JAR. They include
Microsoft Research's MIT notice (`License.txt`), the Eclipse Public License 2.0
and supplemental material (`META-INF/LICENSE.md`), Apache Commons Math license
and notice, and JLine's BSD notice. The embedded license text is authoritative
for each bundled component; the MIT link does not relicense those components.

The explicit prerequisite step copies this input to the recipe's normal cache,
fetches only the seven already-pinned Maven coordinates, and verifies exact
size/digests before publishing. Normal `formal_formats.py prepare` remains
offline and retains all existing JDK, compiler, class, AST and semantic checks.

Embedded notice SHA-256 values:

- `License.txt`: `07533c15531fe2d8d2c37537634fbc1269f3c48e509e81179e7261076dadf617`
- `META-INF/LICENSE.md`: `6e1f002892b81cbe0647019b150c8a056efc1add565671a4f8af629b6cd2cc7b`
- `CommonsMath-LICENSE.txt`: `64f23963615950bad9ddd31569a7f09afbbe11b81d94873ffd9b1cac6081a11d`
- `CommonsMath-NOTICE.txt`: `5495442a32bfc2b93b4a8f2c34c5c218d16cca434aa5684fb953d9419120e3fa`
- `jline-LICENSE.txt`: `7d9ca7d4eb3a2534869e7823b81b299321d49ceb3d169d11b995513fabd9fc7e`
- `org/apache/commons/math3/CommonsMath-LICENSE.txt`: `64f23963615950bad9ddd31569a7f09afbbe11b81d94873ffd9b1cac6081a11d`
- `org/apache/commons/math3/CommonsMath-NOTICE.txt`: `5495442a32bfc2b93b4a8f2c34c5c218d16cca434aa5684fb953d9419120e3fa`
