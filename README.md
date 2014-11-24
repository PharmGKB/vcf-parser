VCF Parser
==========

Strict parser for [VCF](http://en.wikipedia.org/wiki/Variant_Call_Format) 4.1/4.2.

Work in progress.  Code paths in use by PharmGKB are more complete.

The main parser class (`VcfParser`) reads in all metadata and initial position data.  Then actual handling of each position line is delegated to an implementation of `VcfLineParser`.

Check out `VcfParserTest.java` for a quick and dirty view of it in action.
