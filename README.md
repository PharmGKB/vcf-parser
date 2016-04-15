# Overview

This is a strict streaming parser for [VCF](http://en.wikipedia.org/wiki/Variant_Call_Format) 4.1/4.2.

**This is a work in progress.  Code paths in use by PharmGKB are more complete.**

The main parser class ([`VcfParser`](src/main/java/org/pharmgkb/parser/vcf/VcfParser.java)) is responsible for reading all metadata and initial position data.  Then actual handling of each position line is delegated to an implementation of `VcfLineParser`.

Check out [`VcfParserTest.java`](src/test/java/org/pharmgkb/parser/vcf/VcfParserTest.java) for a quick and dirty view of it in action.

[`MemoryMappedVcfLineParser`](src/main/java/org/pharmgkb/parser/vcf/MemoryMappedVcfLineParser.java) is a implementation of `VcfLineParser` that reads everything into memory.


## Get It

### Maven

```xml
<dependencies>
  ...
  <dependency>
    <groupId>org.pharmgkb</groupId>
    <artifactId>vcf-parser</artifactId>
    <version>0.2.0</version>
  </dependency>
  ...
</dependencies>
```

### Non-Maven

You can download jars from the [Central Maven Repository](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.pharmgkb%22%20a%3A%22vcf-parser%22).
