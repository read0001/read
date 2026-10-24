package app.openread.ui.nav

import app.openread.engine.SearchBook

/**
 * Search results carry rule variables that cannot travel in a route, so the tapped one is parked here for the
 * detail screen to pick up.
 */
object Handoff {
    @Volatile var search: SearchBook? = null
}
