package dev.pixelhome

/** Capacity changes before text/touch targets are squeezed; extra items remain in the details sheet. */
data class HomeOverviewPlan(val landscape: Boolean, val columns: Int, val rows: Int, val visibleFavorites: Int, val musicHeight: Float)
fun homeOverviewPlan(width: Float, height: Float, fontScale: Float, count: Int, music: Boolean): HomeOverviewPlan {
    val scale=fontScale.coerceIn(1f,3f)
    val landscape=width>=840 && width>height
    val musicHeight=if(music && !landscape) if(scale>1.3f) 320f else 260f else 0f
    val header=72f*scale
    val gridHeight=(height-header-80f-musicHeight).coerceAtLeast(0f)
    val columns=if(width>=600) 2 else 1
    val rows=if(gridHeight >= 2*(96f+48f*scale)+12f) 2 else 1
    return HomeOverviewPlan(landscape,columns,rows,count.coerceAtLeast(0).coerceAtMost(columns*rows),musicHeight)
}
