#!/usr/bin/env bash
# Probe script for DUPLICATE_METADATA_ID_HANDLING.md's four bcftools/vcf_validator behavior tables.
#
# Per BEHAVIOR_TABLE_STANDARD.md's "Preserving the evidence" section (Tier 2, "Executable"): bcftools
# already has its own Tier-2 evidence source, BcftoolsDependencyTest.java (a real JUnit gate re-run on
# every `./gradlew bcftoolsDependencyTest`). vcf_validator has no such gate -- this script is that
# missing evidence for vcf_validator, plus a bcftools cross-check against the identical payloads so the
# two tools' invocations sit side by side rather than only bcftools's half being reproducible.
#
# This is a plain script, not wired into any Gradle task: it is meant to be re-run by hand (e.g. after a
# vcf_validator or bcftools upgrade) to confirm DUPLICATE_METADATA_ID_HANDLING.md's tables still hold,
# per AGENTS.md's "a probe is not a grammar" -- re-measure, don't regenerate from memory.
#
# Usage: scripts/probe-duplicate-metadata-id-handling.sh   (run from the repo root, or anywhere -- paths
# below are resolved relative to this script's own location)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
FIXTURES="${REPO_ROOT}/src/test/resources/org/pharmgkb/parser/vcf/spec/local-fixtures"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "${WORKDIR}"' EXIT

if ! command -v vcf_validator >/dev/null 2>&1; then
  echo "vcf_validator not found on PATH -- see AGENTS.md for the pinned version (v0.10.2) and checkout." >&2
  exit 1
fi
if ! command -v bcftools >/dev/null 2>&1; then
  echo "bcftools not found on PATH -- see AGENTS.md for the minimum supported version." >&2
  exit 1
fi

echo "vcf_validator: $(vcf_validator --version 2>&1 | head -1)"
echo "bcftools:      $(bcftools --version 2>&1 | head -1)"
echo

FAIL=0

# run_probe <label> <file> <expected vcf_validator verdict: valid|invalid>
run_probe() {
  local label="$1" file="$2" expected="$3"
  local vv_out vv_verdict bt_out bt_exit bt_warned

  vv_out="$(vcf_validator -i "${file}" -l warning -r text -o "${WORKDIR}" 2>&1 || true)"
  if echo "${vv_out}" | grep -q "the input file is valid"; then
    vv_verdict="valid"
  elif echo "${vv_out}" | grep -q "the input file is not valid"; then
    vv_verdict="invalid"
  else
    vv_verdict="UNKNOWN (see raw output below)"
  fi

  bt_out="$(bcftools view "${file}" 2>&1 1>/dev/null || true)"
  bt_exit=0
  bcftools view "${file}" >/dev/null 2>"${WORKDIR}/bt.err" || bt_exit=$?
  if grep -q '\[W::' "${WORKDIR}/bt.err" 2>/dev/null; then bt_warned="warns"; else bt_warned="silent"; fi

  local status="ok"
  if [ "${vv_verdict}" != "${expected}" ]; then
    status="MISMATCH (expected ${expected})"
    FAIL=1
  fi

  printf '%-55s vcf_validator=%-8s [%s]   bcftools exit=%-3s %s\n' \
    "${label}" "${vv_verdict}" "${status}" "${bt_exit}" "${bt_warned}"
  if [ "${status}" != "ok" ]; then
    echo "  --- vcf_validator raw output ---"
    echo "${vv_out}" | sed 's/^/  /'
  fi
}

echo "== Reference table: duplicate declaration ID (rows 1-7; all Valid per vcf_validator) =="
run_probe "##contig  (duplicate-contig-id.vcf)"  "${FIXTURES}/passed/duplicate-contig-id.vcf"  valid
run_probe "##INFO    (duplicate-info-id.vcf)"    "${FIXTURES}/passed/duplicate-info-id.vcf"    valid
run_probe "##FORMAT  (duplicate-format-id.vcf)"  "${FIXTURES}/passed/duplicate-format-id.vcf"  valid
run_probe "##FILTER  (duplicate-filter-id.vcf)"  "${FIXTURES}/passed/duplicate-filter-id.vcf"  valid
run_probe "##ALT     (duplicate-alt-id.vcf)"     "${FIXTURES}/passed/duplicate-alt-id.vcf"     valid
run_probe "##SAMPLE  (duplicate-sample-id.vcf)"  "${FIXTURES}/passed/duplicate-sample-id.vcf"  valid
run_probe "##META    (duplicate-meta-id.vcf)"    "${FIXTURES}/passed/duplicate-meta-id.vcf"    valid
echo

echo "== Contrast table: duplicate-ID vs duplicate #CHROM sample column =="
run_probe "row 1: duplicate ##contig ID (reuses fixture above)" "${FIXTURES}/passed/duplicate-contig-id.vcf" valid
run_probe "row 2: duplicate #CHROM sample column" "${FIXTURES}/failed/duplicate-chrom-sample-column.vcf" invalid
# Row 3 control: a single, well-formed, non-duplicated #CHROM sample column -- deliberately NOT a committed
# fixture (see DUPLICATE_METADATA_ID_HANDLING.md's "Contrast..." section: "not committed as a fixture, since
# it is the ordinary case rather than a duplicate-ID decision"), so it is built here instead.
CONTROL_CHROM="${WORKDIR}/control-single-chrom-sample-column.vcf"
cat > "${CONTROL_CHROM}" <<'EOF'
##fileformat=VCFv4.3
##FORMAT=<ID=GT,Number=1,Type=String,Description="Genotype">
##contig=<ID=chr1,length=1000000>
#CHROM	POS	ID	REF	ALT	QUAL	FILTER	INFO	FORMAT	S1
chr1	100	.	A	T	.	.	.	GT	0/1
EOF
run_probe "row 3 (control): single, non-duplicated #CHROM sample column" "${CONTROL_CHROM}" valid
echo

echo "== Missing/empty ID table (rows 1-7 x {missing, empty}; all Invalid per vcf_validator) =="
run_probe "##contig missing ID" "${FIXTURES}/failed/contig-missing-id.vcf" invalid
run_probe "##contig empty ID"   "${FIXTURES}/failed/contig-empty-id.vcf"   invalid
run_probe "##INFO   missing ID" "${FIXTURES}/failed/info-missing-id.vcf"   invalid
run_probe "##INFO   empty ID"   "${FIXTURES}/failed/info-empty-id.vcf"     invalid
run_probe "##FORMAT missing ID" "${FIXTURES}/failed/format-missing-id.vcf" invalid
run_probe "##FORMAT empty ID"   "${FIXTURES}/failed/format-empty-id.vcf"   invalid
run_probe "##FILTER missing ID" "${FIXTURES}/failed/filter-missing-id.vcf" invalid
run_probe "##FILTER empty ID"   "${FIXTURES}/failed/filter-empty-id.vcf"   invalid
run_probe "##ALT     missing ID" "${FIXTURES}/failed/alt-missing-id.vcf"   invalid
run_probe "##ALT     empty ID"   "${FIXTURES}/failed/alt-empty-id.vcf"     invalid
run_probe "##SAMPLE  missing ID" "${FIXTURES}/failed/sample-missing-id.vcf" invalid
run_probe "##SAMPLE  empty ID"   "${FIXTURES}/failed/sample-empty-id.vcf"   invalid
run_probe "##META    missing ID" "${FIXTURES}/failed/meta-missing-id.vcf"   invalid
run_probe "##META    empty ID"   "${FIXTURES}/failed/meta-empty-id.vcf"     invalid
echo

echo "== Missing/empty ID table, row 8 control: reuses the Reference table's own control row =="
echo "   (each rows-2-7 fixture above already embeds one well-formed ##contig alongside the case under test;"
echo "    no separate probe needed here -- see the Reference table section above and its own note [f]/[g].)"
echo

if [ "${FAIL}" -ne 0 ]; then
  echo "One or more probes produced a vcf_validator verdict different from what" \
       "DUPLICATE_METADATA_ID_HANDLING.md's tables record -- re-check the table before assuming this script drifted." >&2
  exit 1
fi
echo "All probes matched DUPLICATE_METADATA_ID_HANDLING.md's recorded vcf_validator verdicts."
