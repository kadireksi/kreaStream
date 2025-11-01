package com.kreastream

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI

class HdfilmcehennemiPlugin : APIHolder() {
    override val mainApi: MainAPI = Hdfilmcehennemi()
    override val name = "HDFilmCehennemi"
}
