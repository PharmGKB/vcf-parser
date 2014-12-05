VCF Parser
==========

Strict streaming parser for [VCF](http://en.wikipedia.org/wiki/Variant_Call_Format) 4.1/4.2.

This is a work in progress.  Code paths in use by PharmGKB are more complete.

The main parser class (`VcfParser`) is responsible for reading all metadata and initial position data.  Then actual handling of each position line is delegated to an implementation of `VcfLineParser`.

Check out `VcfParserTest.java` for a quick and dirty view of it in action.

There is a default implementation of `VcfLineParser` that reads everything into memory (`MemoryMappedVcfLineParser`).
