---
title: Contributing
---

# Contributing

- The [design](design.md) document explains how scala-go works: the pipeline, the object
  model, Go interop, the runtime, the status and the roadmap.
- The repository's `README.md` describes its layout and how to build and test it
  (`mill linker.test` runs the end-to-end tests).
- `mill docs.site` builds this site, with the API documentation, into `out/docs/site.dest`.
  Its pages are in `docs/_docs`, and the sidebar in `docs/sidebar.yml`.
