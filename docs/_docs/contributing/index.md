---
title: Contributing
---

# Contributing

- [Design](design.md): how scala-go works: the pipeline, the object model, Go interop, the
  runtime.
- [Roadmap](roadmap.md): the known gaps and bugs, by priority.
- The repository's `README.md`: its layout, and how to build and test it (`./mill linker.test`
  runs the end-to-end tests).
- `./mill docs.site` builds this site, with the API documentation, into `out/docs/site.dest`
  (open `docs/index.html`). Pages are in `docs/_docs`, the sidebar in `docs/sidebar.yml`.
