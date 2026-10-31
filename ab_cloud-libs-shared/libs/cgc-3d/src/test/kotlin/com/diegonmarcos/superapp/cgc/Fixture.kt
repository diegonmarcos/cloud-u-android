package com.diegonmarcos.superapp.cgc

/** A small declaration with one category of every kind, a declared-soon colour category and an unwired kind. */
object Fixture {
    const val JSON = """{
      "scene": {"model": {"url": "m.glb"}},
      "engine": "model-file",
      "default": {"top": "navy", "skin": "original", "glasses": "on", "face": "neutral", "anim": "idle-opt", "hair": "long", "later": "black"},
      "categories": [
        {"id": "top", "label": "Hoodie", "kind": "color", "targets": ["outfit_top", "hood"], "options": [
          {"id": "original", "label": "Original", "color": null},
          {"id": "navy", "label": "Navy", "color": "#22345e"},
          {"id": "forest", "label": "Forest", "color": "#2f5e3c"},
          {"id": "snow", "label": "Snow", "color": "#ececec"}]},
        {"id": "skin", "label": "Skin", "kind": "tint", "targets": ["AvatarHead"], "options": [
          {"id": "original", "label": "Original", "color": null},
          {"id": "tan", "label": "Tan", "color": "#e3ab86"}]},
        {"id": "glasses", "label": "Glasses", "kind": "visible", "options": [
          {"id": "on", "label": "On", "visible": {"glasses": true}},
          {"id": "off", "label": "Off", "visible": {"glasses": false}}]},
        {"id": "face", "label": "Face", "kind": "morph", "options": [
          {"id": "neutral", "label": "Neutral"},
          {"id": "smile", "label": "Smile", "morphs": {"mouthSmileLeft": 0.8, "mouthSmileRight": 0.8}},
          {"id": "shout", "label": "Shout", "morphs": {"jawOpen": 1.7}}]},
        {"id": "anim", "label": "Animation", "kind": "clip", "options": [
          {"id": "idle-opt", "label": "Idle", "clip": "idle"},
          {"id": "dance-opt", "label": "Dance", "clip": "dance"}]},
        {"id": "hair", "label": "Hair", "kind": "parts", "options": [
          {"id": "long", "label": "Long"}, {"id": "bun", "label": "Bun"}]},
        {"id": "later", "label": "Later", "kind": "color", "soon": true, "targets": ["shoes"], "options": [
          {"id": "black", "label": "Black", "color": "#000000"}, {"id": "white", "label": "White", "color": "#ffffff"}]}
      ],
      "ai": {"id": "openrouter", "endpoint": "https://example.invalid/v1/chat/completions", "model": "test/model", "key_slot": "openrouter",
             "referer": "https://example.invalid/app", "title": "Test"},
      "ui": {"soon": "Coming soon", "_doc": "ignored"}
    }"""

    val spec: CgcSpec = CgcSpec.parse(JSON)
}
