# hts-specs: "Non-valid URL" fixtures don't test a real, checkable rule

## Summary

Several `failed/` conformance fixtures under `test/vcf/{4.1,4.2,4.3}/` use
`##CauseOfFailure=Non-valid URL` for an `##assembly=`/`##pedigreeDB=` value whose only
distinguishing feature is a numeric-looking hostname (`8080`). That string is syntactically
valid per RFC 3986 (a `reg-name` may be all-digits) and is accepted without complaint by both
`java.net.URI`/`URL` (OpenJDK 25) and `bcftools view` (v1.23.1, htslib 1.23.1) — i.e. by both a
standard URI parser and the reference VCF implementation this project treats as authoritative
when the spec text is silent. Neither VCFv4.1, VCFv4.2, nor VCFv4.3 defines a formal grammar for
the `url` value in `##assembly=url` / `##pedigreeDB=URL` (VCFv4.2.tex describes both only as
prose examples, with no BNF or validity rule).

There doesn't appear to be a real, generalizable rule these fixtures are testing — `8080` looks
like it was chosen because it resembles a port number rather than a hostname, but nothing in the
URI grammar or in mainstream tooling treats that as invalid. Filing this so it can be
clarified or reconsidered, rather than guessing at a heuristic (e.g. "reject all-digit
hostnames") that no spec or tool actually enforces.

## Verification performed

```
$ jshell
jshell> new java.net.URI("ftp://8080:8080/not-valid/host/to/file.fastq")
==> host=8080 port=8080 scheme=ftp   -- parses without error
```

```
$ bcftools --version
bcftools 1.23.1
Using htslib 1.23.1

$ bcftools view test/vcf/4.2/failed/failed_meta_pedigreedb_002.vcf
##fileformat=VCFv4.2
##FILTER=<ID=PASS,Description="All filters passed">
##CauseOfFailure=Non-valid URL
##pedigreeDB=ftp://8080:8080/not-valid/host/to/pedigreeDB
...
```
No error or warning from bcftools about the `##pedigreeDB=`/`##assembly=` line's value.

## Affected fixtures

### `##assembly=ftp://8080:8080/not-valid/host/to/file.fastq`

| File                                               | `##fileformat=` | `##CauseOfFailure=`                       |
|----------------------------------------------------|-----------------|-------------------------------------------|
| `test/vcf/4.1/failed/failed_meta_assembly_001.vcf` | `VCFv4.1`       | `Non-valid URL`                           |
| `test/vcf/4.2/failed/failed_meta_assembly_001.vcf` | `VCFv4.3`       | `Meta-data entry is not a key-value pair` |
| `test/vcf/4.3/failed/failed_meta_assembly_001.vcf` | `VCFv4.3`       | `Meta-data entry is not a key-value pair` |

**Note:** `test/vcf/4.2/failed/failed_meta_assembly_001.vcf` lives under the `4.2/` directory but internally declares `VCFv4.3`.


### `##pedigreeDB=ftp://8080:8080/not-valid/host/to/pedigreeDB`

| File                                                 | `##fileformat=` | `##CauseOfFailure=` |
|------------------------------------------------------|-----------------|---------------------|
| `test/vcf/4.1/failed/failed_meta_pedigreedb_002.vcf` | `VCFv4.1`       | `Non-valid URL`     |
| `test/vcf/4.2/failed/failed_meta_pedigreedb_002.vcf` | `VCFv4.2`       | `Non-valid URL`     |
| `test/vcf/4.3/failed/failed_meta_pedigreedb_002.vcf` | `VCFv4.3`       | `Non-valid URL`     |

These three are internally consistent with each other (same cause across all three versions),
unlike the `assembly_001` family above — but all three still rest on the same unverified
"non-valid URL" premise addressed above.

## Suggested resolution (up to hts-specs maintainers)

- Either specify what makes a URL invalid here (a formal grammar, or a documented convention this
  project isn't aware of), so a conformant validator has something concrete to check, or
  drop/retire these fixtures as testing an unspecified, unenforced rule.
- Separately, fix `test/vcf/4.2/failed/failed_meta_assembly_001.vcf`'s internal
  `##fileformat=VCFv4.3` mismatch with its own directory, and reconcile its
  `##CauseOfFailure=` with its 4.1/4.3 siblings so the same content is labeled consistently
  across versions.

## Context

Found while extending [PharmGKB/vcf-parser](https://github.com/PharmGKB/vcf-parser)'s hts-specs
conformance test to close its "malformed metadata key-value pair" baseline cluster. This project
treats hts-specs' conformance suite as spec guidance (see its `AGENTS.md`), so an unclear or
unenforceable fixture in that suite is worth resolving upstream rather than guessing at a rule to
satisfy it downstream.
