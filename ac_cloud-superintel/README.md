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
