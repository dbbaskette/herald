These fixtures are synthetic; no live endpoint, model or key is included.

Schema evidence reviewed 2026-09-11:
- Broadcom article 438025 recommends `genai`/`llm` tags across offering renames:
  https://knowledge.broadcom.com/external/article/438025/gen-ai-tile-plan-is-renamed-to-aimodels.html
- Its linked AI Services 10.3 schema page returned HTTP 403. A target broker version
  was not supplied, so live foundation compatibility remains unverified.
- The Goose Tanzu integration guide (Goose 1.28.0+) corroborates the top-level
  single-model tuple, nested endpoint tuple, and endpoint-relative request path:
  https://goose-docs.ai/docs/guides/tanzu-ai-services/

The complete supported contract, URL adaptation and evidence limits are in
../../../../../docs/genai-binding.md (repository docs/genai-binding.md).
