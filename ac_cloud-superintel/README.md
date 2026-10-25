# Cloud SuperIntel (SI)

The fleet's Gemini-style chat app (#1162): a chat screen, a left drawer of sessions (new chat, rename, delete), the model and
agent pickers, answers streaming in, replies drawn as Markdown.

* The chat is `libs:ai` (shared with Cloud Drive, Cloud Code and the SuperApp assistant): the page, the sessions store, the
  streaming, the Markdown reader (`AiMarkdown`) and the drawer body (`AiSessionsPanel`). This app only assembles it.
* The shell is the fleet's: `libs:nav-bar-top-first-row` (Profile at the top right opens Cloud Account), `libs:nav-bar-top-second-row`
  (page tabs with icons), `libs:nav-bar-bottom`, `libs:theme-background` and the shared scroll collapse. Sections and pages are
  `build.json::ui`.
* Backend: the fleet agent gateway (my-ai-api, mesh only): Claude through claude-superset-api on oci-apps, Hermes, Goose, OpenClaw and the
  OpenRouter models. Its address is baked from the fleet catalogue (`libs/ai/ai-gateway.gradle`); this app holds no token and the repository
  holds none.
* Package `com.diegonmarcos.cloudsuperintel`, tile `SI` in Cloud > Apps > AGI (`SI AI | Research Code | Tools | MyTerminal Terminal`).

## Sections (bottom nav: Notebook, SI, News)

* **SI**: one persona chat per entry of `app/src/main/assets/si/personas.json` (SI router, Scientist, CTO, Doctor, Lawyer, Asset Manager, Wiki AI), each
  with its declared system prompt (sent on every request through `AiStandardHost(system=)`, never stored), suggested prompts, agent, preferred models
  and Web switch. The second row and `build.json::ui` carry the same ids; `test/test-superintel-app.sh` holds them in step.
* **Notebook** (`assets/si/notebook.json`): sources (notes, web pages, text files from any storage through the system picker) kept on the phone;
  answers come only from them, with `[n]` citations and the passage shown. No passage that holds enough of the question's terms = the model is not
  called and the answer is "This is not in your sources."; an answer without a valid citation is replaced by the same line. Retrieval is hybrid when the
  fleet embeddings + rerank service answers (embeddings-api on the mesh: bge-m3 dense candidates plus BM25 hits, re-scored by bge-reranker-v2-m3; the
  reranker score floor keeps grounding strict) and BM25 on the phone otherwise. A PDF source is turned into text by the same service (POST /pdf, mesh
  only; off the mesh the file is refused). Studio: summary, FAQ and study notes over the sources.
* **News** (`assets/si/news.json`): Discover-style topic feeds (For you, Tech, Science, Finance, World, Health, Sports, Arts) from the fleet's
  news-gdelt service (the `news` entry of `ac_cloud-account/build.json::fleet_endpoints`: mesh first, public + fleet bearer after it; baked as `BuildConfig.NEWS_MESH_URL` / `NEWS_PUBLIC_URL`); headlines about one event are merged into
  a story with its source count; the card and story summaries are written by the gateway from those headlines only. An unreachable backend shows an
  empty state, never invented stories. The follow-up box drops its question, with the story's sources, into the SI chat box.
* JVM tests (`app/src/test`): personas from data, notebook refusal and citation checks, news parsing and grouping.
