# Cloud SuperIntel (SI)

The fleet's Gemini-style chat app (#1162): a chat screen, a left drawer of sessions (new chat, rename, delete), the model and
agent pickers, answers streaming in, replies drawn as Markdown.

* The chat is `libs:ai` (shared with Cloud Drive, Cloud Code and the SuperApp assistant): the page, the sessions store, the
  streaming, the Markdown reader (`AiMarkdown`) and the drawer body (`AiSessionsPanel`). This app only assembles it.
* The shell is the fleet's: `libs:ui-nav-bars` (Profile at the top right opens Cloud Account), `libs:ui-nav-bars`
  (page tabs with icons), `libs:ui-nav-bars`, `libs:theme-background` and the shared scroll collapse. Sections and pages are
  `build.json::ui`.
* Backend: the fleet agent gateway (my-ai-api, mesh only): Claude through claude-superset-api on oci-apps, Hermes, Goose, OpenClaw and the
  OpenRouter models. Its address is baked from the fleet catalogue (`libs/ai/ai-gateway.gradle`); this app holds no token and the repository
  holds none.
* Package `com.diegonmarcos.cloudsuperintel`, tile `SI` in Cloud > Apps > AGI (`SI AI | Research Code | Tools | MyTerminal Terminal`).

## Sections (bottom nav: Notebook, SI, CGCs)

* **SI**: the router persona's chat (`app/src/main/assets/si/personas.json`, `si`): it answers as the best-fitting CGC expert and says which.
* **CGCs**: the experts, one persona chat each, in `horizontal_two_rows` mode (libs:ui-nav-bars; the user may switch to one row or the side menu in
  Configs), six groups declared in `build.json::ui`: Deep Science (Math, Physics, Chemistry), Engineers (Energy, Manufacturing, Robotics), Production
  (CTO), Medicine (Biology, Neuroscience), Business (Lawyer, Asset Manager), General (Wiki AI). Each persona declares its system prompt (sent on
  every request through `AiStandardHost(system=)`, never stored), suggested prompts, preferred agent, models and MCPs, and Web switch; the old Scientist
  is Math, Physics and Chemistry, the old Doctor is Biology and Neuroscience. `test/test-superintel-app.sh` and `PersonasTest` hold the ids and the
  groups in step with `build.json::ui`.
* **Notebook** (`assets/si/notebook.json`): sources (notes, web pages, text files from any storage through the system picker) kept on the phone;
  answers come only from them, with `[n]` citations and the passage shown. No passage that holds enough of the question's terms = the model is not
  called and the answer is "This is not in your sources."; an answer without a valid citation is replaced by the same line. Retrieval is hybrid when the
  fleet embeddings + rerank service answers (embeddings-api on the mesh: bge-m3 dense candidates plus BM25 hits, re-scored by bge-reranker-v2-m3; the
  reranker score floor keeps grounding strict) and BM25 on the phone otherwise. A PDF source is turned into text by the same service (POST /pdf, mesh
  only; off the mesh the file is refused). Studio: summary, FAQ and study notes over the sources.
* The chat page is libs:ai's (Cloud Code's design): the composer's icon row (Task, Agent, Model, Mode, MCPs) is enabled in `app/src/main/assets/ai/composer.json`.
* JVM tests (`app/src/test`): personas and CGC groups from data, the composer declaration, notebook refusal and citation checks.
