package app.sterna.push

/**
 * What the Delete button of a new-mail banner is to do — see [bannerDeleteAct].
 */
sealed interface BannerDeleteAct {
    /**
     * Nothing at all: no move, no local eviction, and the banner STAYS UP — a banner that does not
     */
    data object DoNothing : BannerDeleteAct

    /** Move to Trash, exactly as this button always has, and then take the banner down. */
    data object MoveToTrash : BannerDeleteAct
}

/**
 * Whether a banner's Delete would actually MOVE the message, decided from the local caches alone.
 */
fun bannerDeleteAct(cachedRole: String?, hasCachedTrash: Boolean): BannerDeleteAct = when {
    cachedRole == TRASH_ROLE -> BannerDeleteAct.DoNothing
    cachedRole != null && !hasCachedTrash -> BannerDeleteAct.DoNothing
    else -> BannerDeleteAct.MoveToTrash
}

private const val TRASH_ROLE = "trash"
