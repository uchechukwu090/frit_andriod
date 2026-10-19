# Vendored third-party source

This directory is a **vendored copy** of upstream llama.cpp, not a git submodule.

- Upstream: https://github.com/ggerganov/llama.cpp.git
- Vendored commit: `96013c51121fcabbe00b70095f104962abe1ec14`
- Why vendored and not a submodule: this tree was originally copied in from
  another checkout, which left its own `.git` index out of sync with the files
  on disk (2,194 phantom deletions + 2,051 untracked entries, and **zero**
  genuinely modified files). Rather than commit a broken submodule pointer,
  the sources are tracked directly so a plain `git clone` gives a tree that
  builds — no `git submodule update --init`, no network, no risk of an
  upstream force-push breaking the build.
- The upstream history is NOT part of this repository. To compare against
  upstream, clone it separately and diff against the commit above.

Audit note F-20: this tree is excluded from the app audit — do not audit or
reformat it.
