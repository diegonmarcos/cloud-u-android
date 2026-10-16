package com.diegonmarcos.superapp.browser

/** One suggestion on the fill bar. [badge] says where the value comes from ("local", "vault", "this site"). */
data class AcChip(val id: String, val text: String, val badge: String? = null)

/** The fill bar above the keyboard: chips, and a note (why Cloud Vault was not asked, say). */
data class AcBar(val chips: List<AcChip>, val note: String? = null)

/** The chooser: which profile, and which value per kind when a profile has several. */
class AcChooserState(val title: String, val profiles: List<AcProfile>, val plan: AcChoicePlan, val fields: List<AcField>, val initial: AcChoice)

/** The save prompt after a submit: what could be remembered, and the profiles it could go into. */
class AcSaveState(val site: String, val proposals: List<AcProposal>, val profiles: List<AcProfile>, val vaultNote: String?)
